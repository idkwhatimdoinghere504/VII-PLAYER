(() => {
  const $ = selector => document.querySelector(selector);
  const audio = $('#audio');
  const filePicker = $('#filePicker');
  const tracks = [];
  let currentIndex = -1;
  let shuffle = false;
  let repeat = 0;
  let timerId = null;
  let audioContext = null;
  let bassFilter = null;
  let view = 'tracks';

  const titleMap = {tracks:'All tracks',albums:'Albums',artists:'Artists',playlists:'Playlists',queue:'Play queue'};
  const formatTime = seconds => {
    if (!Number.isFinite(seconds)) return '0:00';
    const mins = Math.floor(seconds / 60);
    return `${mins}:${String(Math.floor(seconds % 60)).padStart(2,'0')}`;
  };
  const cleanName = name => name.replace(/\.[^.]+$/, '').replace(/[_]+/g, ' ').trim() || 'Untitled track';
  const parseTrack = file => {
    const label = cleanName(file.name);
    const split = label.split(/\s+[–—-]\s+/);
    return { file, title: split.length > 1 ? split.slice(1).join(' - ') : label, artist: split.length > 1 ? split[0] : 'Local track', url: URL.createObjectURL(file), added: Date.now(), id: `${file.name}-${file.size}-${file.lastModified}` };
  };

  function filteredTracks() {
    const q = $('#searchInput').value.trim().toLowerCase();
    let result = tracks.filter(track => `${track.title} ${track.artist} ${track.file.name}`.toLowerCase().includes(q));
    if ($('#sortSelect').value === 'newest') result = result.slice().reverse();
    else result = result.slice().sort((a,b) => a.title.localeCompare(b.title));
    return result;
  }

  function render() {
    const visible = filteredTracks();
    $('#trackCount').textContent = tracks.length;
    $('#visibleCount').textContent = visible.length;
    $('#welcomePanel').hidden = tracks.length > 0;
    $('#emptyState').hidden = visible.length > 0;
    $('#compatNote').hidden = tracks.length === 0;
    const list = $('#trackList');
    list.replaceChildren();
    visible.forEach((track, index) => {
      const actualIndex = tracks.indexOf(track);
      const row = document.createElement('button');
      row.type = 'button';
      row.className = `track-row${actualIndex === currentIndex ? ' is-playing' : ''}`;
      row.setAttribute('aria-label', `Play ${track.title} by ${track.artist}`);
      const number = document.createElement('span'); number.className = 'row-number'; number.textContent = actualIndex === currentIndex && !audio.paused ? '♫' : String(index + 1).padStart(2,'0');
      const main = document.createElement('span'); main.className = 'row-main';
      const cover = document.createElement('span'); cover.className = 'row-cover'; cover.textContent = '♫';
      const text = document.createElement('span'); text.className = 'row-text';
      const title = document.createElement('strong'); title.textContent = track.title;
      const artist = document.createElement('small'); artist.textContent = track.artist;
      text.append(title,artist); main.append(cover,text);
      const type = document.createElement('span'); type.className = 'file-type'; type.textContent = track.file.name.split('.').pop().toUpperCase();
      const added = document.createElement('span'); added.className = 'added-time'; added.textContent = 'This session';
      const more = document.createElement('span'); more.className = 'row-more'; more.textContent = '•••'; more.setAttribute('aria-hidden','true');
      row.append(number,main,type,added,more);
      row.addEventListener('click', () => playTrack(actualIndex));
      list.append(row);
    });
    $('#viewTitle').textContent = titleMap[view];
    $('#sectionTitle').childNodes[0].textContent = view === 'tracks' ? 'Tracks ' : `${titleMap[view]} `;
  }

  function addFiles(fileList) {
    const incoming = [...fileList].filter(file => file.type.startsWith('audio/') || /\.(mp3|flac|wav|m4a|aac|ogg|opus|aiff?)$/i.test(file.name));
    let added = 0;
    incoming.forEach(file => {
      const id = `${file.name}-${file.size}-${file.lastModified}`;
      if (!tracks.some(track => track.id === id)) { tracks.push(parseTrack(file)); added++; }
    });
    if (added) {
      if (currentIndex < 0) $('#playStatus').innerHTML = '<span class="status-dot"></span> Ready to play';
      render();
    }
    filePicker.value = '';
  }

  async function setupAudioEffects() {
    if (audioContext) { if (audioContext.state === 'suspended') await audioContext.resume(); return; }
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) return;
    try {
      audioContext = new AudioContextClass();
      const source = audioContext.createMediaElementSource(audio);
      bassFilter = audioContext.createBiquadFilter();
      bassFilter.type = 'lowshelf'; bassFilter.frequency.value = 120; bassFilter.gain.value = Number($('#bassRange').value);
      source.connect(bassFilter).connect(audioContext.destination);
    } catch (error) { console.warn('Audio effects unavailable in this browser.', error); }
  }

  async function playTrack(index) {
    if (!tracks.length) return;
    currentIndex = (index + tracks.length) % tracks.length;
    audio.src = tracks[currentIndex].url;
    audio.playbackRate = Number($('#speedSelect').value);
    await setupAudioEffects();
    try {
      await audio.play();
      $('#playStatus').innerHTML = '<span class="status-dot"></span> Playing';
      if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = new MediaMetadata({title:tracks[currentIndex].title,artist:tracks[currentIndex].artist,album:'MixPlayer'});
        navigator.mediaSession.setActionHandler('play', () => audio.play());
        navigator.mediaSession.setActionHandler('pause', () => audio.pause());
        navigator.mediaSession.setActionHandler('previoustrack', previous);
        navigator.mediaSession.setActionHandler('nexttrack', next);
      }
    } catch (error) {
      $('#playStatus').innerHTML = '<span class="status-dot" style="background:#e49a72"></span> Format may not be supported';
    }
    updateTrackMeta(); render();
  }

  function updateTrackMeta() {
    const track = tracks[currentIndex];
    if (!track) return;
    $('#currentTitle').textContent = track.title;
    $('#currentArtist').textContent = track.artist;
    $('#albumArt').setAttribute('aria-label', `Artwork for ${track.title}`);
  }
  function togglePlay() {
    if (currentIndex < 0) return tracks.length ? playTrack(0) : filePicker.click();
    if (audio.paused) audio.play().then(() => { $('#playStatus').innerHTML = '<span class="status-dot"></span> Playing'; }).catch(() => {});
    else audio.pause();
  }
  function next() {
    if (!tracks.length) return;
    const index = shuffle && tracks.length > 1 ? (currentIndex + 1 + Math.floor(Math.random() * (tracks.length - 1))) % tracks.length : currentIndex + 1;
    playTrack(index >= tracks.length ? 0 : index);
  }
  function previous() { if (audio.currentTime > 3) audio.currentTime = 0; else playTrack(currentIndex <= 0 ? tracks.length - 1 : currentIndex - 1); }

  $('#importButton').addEventListener('click', () => filePicker.click());
  $('#mobileImport').addEventListener('click', () => filePicker.click());
  $('#startImport').addEventListener('click', () => filePicker.click());
  $('#emptyImport').addEventListener('click', () => filePicker.click());
  filePicker.addEventListener('change', event => addFiles(event.target.files));
  $('#searchInput').addEventListener('input', render);
  $('#sortSelect').addEventListener('change', render);
  $('#playButton').addEventListener('click', togglePlay);
  $('#nextButton').addEventListener('click', next);
  $('#previousButton').addEventListener('click', previous);
  $('#shuffleAll').addEventListener('click', () => { if (tracks.length) { shuffle = true; $('#shuffleButton').classList.add('selected'); playTrack(Math.floor(Math.random()*tracks.length)); } });
  $('#shuffleButton').addEventListener('click', () => { shuffle = !shuffle; $('#shuffleButton').classList.toggle('selected',shuffle); });
  $('#repeatButton').addEventListener('click', () => { repeat = (repeat + 1) % 3; audio.loop = repeat === 2; const label = ['Repeat off','Repeat all','Repeat one'][repeat]; $('#repeatButton').title = label; $('#repeatButton').setAttribute('aria-label',label); $('#repeatButton').classList.toggle('selected',repeat > 0); });
  $('#volumeBar').addEventListener('input', event => { audio.volume = Number(event.target.value); });
  audio.volume = Number($('#volumeBar').value);
  $('#seekBar').addEventListener('input', event => { if (Number.isFinite(audio.duration)) audio.currentTime = audio.duration * Number(event.target.value) / 1000; });
  audio.addEventListener('timeupdate', () => { $('#currentTime').textContent = formatTime(audio.currentTime); $('#seekBar').value = audio.duration ? Math.round(audio.currentTime / audio.duration * 1000) : 0; });
  audio.addEventListener('loadedmetadata', () => { $('#duration').textContent = formatTime(audio.duration); });
  audio.addEventListener('play', () => { $('#playButton').textContent = 'Ⅱ'; $('#playButton').setAttribute('aria-label','Pause'); render(); });
  audio.addEventListener('pause', () => { $('#playButton').textContent = '▶'; $('#playButton').setAttribute('aria-label','Play'); $('#playStatus').innerHTML = '<span class="status-dot"></span> Paused'; render(); });
  audio.addEventListener('ended', () => { if (repeat === 1 && !audio.loop) next(); else if (!audio.loop) { $('#playStatus').innerHTML = '<span class="status-dot"></span> Ready'; render(); } });
  audio.addEventListener('error', () => { if (audio.src) $('#playStatus').innerHTML = '<span class="status-dot" style="background:#e49a72"></span> Could not play this format'; });

  document.querySelectorAll('.nav-item[data-view]').forEach(button => button.addEventListener('click', () => {
    view = button.dataset.view;
    document.querySelectorAll('.nav-item[data-view]').forEach(item => item.classList.toggle('active', item === button));
    render();
    if (view === 'albums' || view === 'artists' || view === 'playlists') {
      $('#sectionEyebrow').textContent = 'LIBRARY VIEW';
      $('#emptyState').hidden = false;
      $('#emptyState h3').textContent = view === 'playlists' ? 'Playlists are next' : `${titleMap[view]} need track tags`;
      $('#emptyState p').textContent = 'This first build reads audio filenames. Embedded tag and playlist editing will be added in a later pass.';
      $('#trackList').replaceChildren(); $('#visibleCount').textContent = '0';
    } else { $('#sectionEyebrow').textContent = 'YOUR COLLECTION'; $('#emptyState h3').textContent = 'Your music will show up here'; $('#emptyState p').textContent = 'Add MP3, FLAC, WAV, M4A, OGG, or other browser-supported audio files.'; render(); }
  }));

  $('#settingsButton').addEventListener('click', () => $('#settingsDialog').showModal());
  $('#queueToggle').addEventListener('click', () => { view = 'queue'; render(); });
  $('#accentPicker').addEventListener('input', event => document.documentElement.style.setProperty('--accent',event.target.value));
  $('#speedSelect').addEventListener('change', event => { audio.playbackRate = Number(event.target.value); });
  $('#bassRange').addEventListener('input', async event => { await setupAudioEffects(); if (bassFilter) bassFilter.gain.value = Number(event.target.value); });
  $('#likeButton').addEventListener('click', event => { const liked = event.currentTarget.classList.toggle('liked'); event.currentTarget.textContent = liked ? '♥' : '♡'; event.currentTarget.setAttribute('aria-label',liked ? 'Unlike track' : 'Like track'); });
  $('#timerButton').addEventListener('click', () => $('#timerDialog').showModal());
  $('#timerDialog').addEventListener('close', () => {
    const choice = $('#timerDialog').returnValue;
    if (timerId) clearTimeout(timerId);
    if (choice && choice !== 'cancel') { timerId = setTimeout(() => { audio.pause(); $('#playStatus').innerHTML = '<span class="status-dot"></span> Sleep timer ended'; }, Number(choice) * 60 * 1000); $('#playStatus').innerHTML = `<span class="status-dot"></span> Stops in ${choice} min`; }
  });
  $('#searchInput').addEventListener('keydown', event => { if (event.key === 'Escape') event.currentTarget.value = ''; render(); });
  document.addEventListener('keydown', event => { if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); $('#searchInput').focus(); } });
  document.addEventListener('dragover', event => event.preventDefault());
  document.addEventListener('drop', event => { event.preventDefault(); if (event.dataTransfer.files.length) addFiles(event.dataTransfer.files); });
  if ('serviceWorker' in navigator && location.protocol !== 'file:') navigator.serviceWorker.register('./sw.js').catch(() => {});
  render();
})();
