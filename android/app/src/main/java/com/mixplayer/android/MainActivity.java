package com.mixplayer.android;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

public final class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 41;
    private static final int INK = Color.rgb(244, 241, 235);
    private static final int MUTED = Color.rgb(160, 157, 151);
    private static final int BG = Color.rgb(18, 19, 21);
    private static final int PANEL = Color.rgb(31, 32, 34);
    private static final int ACCENT = Color.rgb(216, 169, 101);

    private final List<Track> library = new ArrayList<>();
    private final Set<Long> favorites = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = command -> handler.post(command);
    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private LinearLayout root;
    private LinearLayout list;
    private TextView status;
    private TextView nowPlaying;
    private TextView playButton;
    private SeekBar seekBar;
    private EditText search;
    private String activeTab = "Tracks";
    private String filter = "";
    private boolean userSeeking;
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (controller != null && controller.isConnected()) {
                long duration = controller.getDuration();
                if (duration > 0 && !userSeeking) seekBar.setProgress((int) (1000L * controller.getCurrentPosition() / duration));
                updateNowPlaying();
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        Set<String> storedFavorites = getPreferences(MODE_PRIVATE).getStringSet("favorites", Collections.emptySet());
        for (String value : storedFavorites) {
            try { favorites.add(Long.parseLong(value)); } catch (NumberFormatException ignored) { }
        }
        buildUi();
        connectPlayback();
        ensurePermissionAndLoad();
        handler.post(progressTick);
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.setBackgroundColor(BG);
        setContentView(root);

        TextView brand = text("MIXPLAYER", 13, ACCENT, true);
        brand.setLetterSpacing(.18f);
        root.addView(brand, matchWrap());
        TextView heading = text("Your music, your way", 26, INK, true);
        LinearLayout.LayoutParams headingLp = matchWrap(); headingLp.topMargin = dp(7);
        root.addView(heading, headingLp);
        TextView subtitle = text("A local music player for Android", 14, MUTED, false);
        root.addView(subtitle, matchWrap());

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search tracks, artists, albums");
        search.setHintTextColor(MUTED);
        search.setTextColor(INK);
        search.setTextSize(15);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(roundRect(PANEL, 14));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(48)); searchLp.topMargin = dp(18);
        root.addView(search, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter = s.toString().trim().toLowerCase(Locale.ROOT); renderLibrary(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"Tracks", "Albums", "Artists", "Favorites"};
        for (String name : names) {
            Button tab = button(name, name.equals(activeTab) ? ACCENT : PANEL, name.equals(activeTab) ? BG : INK);
            tab.setOnClickListener(v -> { activeTab = name; updateTabs(tabs, names); renderLibrary(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1); lp.setMargins(dp(2), dp(14), dp(2), dp(10));
            tabs.addView(tab, lp);
        }
        root.addView(tabs, matchWrap());

        status = text("Looking for music…", 13, MUTED, false);
        root.addView(status, matchWrap());
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0, dp(6), 0, dp(10));
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout player = new LinearLayout(this); player.setOrientation(LinearLayout.VERTICAL); player.setPadding(dp(14), dp(10), dp(14), dp(10)); player.setBackground(roundRect(PANEL, 18));
        LinearLayout.LayoutParams playerLp = new LinearLayout.LayoutParams(-1, -2); playerLp.topMargin = dp(8);
        root.addView(player, playerLp);
        nowPlaying = text("Nothing playing", 15, INK, true); player.addView(nowPlaying, matchWrap());
        seekBar = new SeekBar(this); seekBar.setMax(1000); seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        player.addView(seekBar, new LinearLayout.LayoutParams(-1, dp(28)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar bar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (controller != null && controller.getDuration() > 0) controller.seekTo(controller.getDuration() * bar.getProgress() / 1000L);
                userSeeking = false;
            }
        });
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER); controls.setOrientation(LinearLayout.HORIZONTAL);
        Button shuffle = button("SHUFFLE", BG, MUTED); shuffle.setOnClickListener(v -> { if (controller != null) controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled()); });
        Button previous = button("|◀", BG, INK); previous.setOnClickListener(v -> { if (controller != null) controller.seekToPreviousMediaItem(); });
        playButton = text("▶", 24, ACCENT, true); playButton.setGravity(Gravity.CENTER); playButton.setOnClickListener(v -> togglePlayback());
        Button next = button("▶|", BG, INK); next.setOnClickListener(v -> { if (controller != null) controller.seekToNextMediaItem(); });
        Button repeat = button("REPEAT", BG, MUTED); repeat.setOnClickListener(v -> { if (controller != null) controller.setRepeatMode((controller.getRepeatMode() + 1) % 3); });
        controls.addView(shuffle, new LinearLayout.LayoutParams(0, dp(44), 1)); controls.addView(previous, new LinearLayout.LayoutParams(0, dp(44), 1)); controls.addView(playButton, new LinearLayout.LayoutParams(0, dp(44), 1)); controls.addView(next, new LinearLayout.LayoutParams(0, dp(44), 1)); controls.addView(repeat, new LinearLayout.LayoutParams(0, dp(44), 1));
        player.addView(controls);
    }

    private void updateTabs(LinearLayout tabs, String[] names) {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            Button b = (Button) tabs.getChildAt(i); boolean selected = names[i].equals(activeTab);
            b.setBackground(roundRect(selected ? ACCENT : PANEL, 12)); b.setTextColor(selected ? BG : INK);
        }
    }

    private void ensurePermissionAndLoad() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) loadLibrary();
        else requestPermissions(new String[]{permission}, REQUEST_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) loadLibrary();
            else status.setText("Allow audio access to show music stored on this device.");
        }
    }

    private void loadLibrary() {
        library.clear();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION};
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0";
        try (Cursor cursor = getContentResolver().query(collection, projection, selection, null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cursor != null) while (cursor.moveToNext()) {
                long id = cursor.getLong(0); String title = cursor.getString(1); String artist = cursor.getString(2); String album = cursor.getString(3);
                long albumId = cursor.getLong(4); long duration = cursor.getLong(5);
                Uri uri = ContentUris.withAppendedId(collection, id);
                library.add(new Track(id, uri, safe(title, "Unknown track"), safe(artist, "Unknown artist"), safe(album, "Unknown album"), albumId, duration));
            }
            status.setText(library.size() + (library.size() == 1 ? " track" : " tracks"));
        } catch (SecurityException exception) {
            status.setText("Music access was not granted.");
        }
        renderLibrary();
    }

    private void renderLibrary() {
        if (list == null) return;
        list.removeAllViews();
        List<Track> shown = new ArrayList<>();
        for (Track track : library) {
            if (activeTab.equals("Favorites") && !favorites.contains(track.id)) continue;
            if (activeTab.equals("Albums") && shown.stream().anyMatch(t -> t.album.equals(track.album) && t.artist.equals(track.artist))) continue;
            if (activeTab.equals("Artists") && shown.stream().anyMatch(t -> t.artist.equals(track.artist))) continue;
            String text = (track.title + " " + track.artist + " " + track.album).toLowerCase(Locale.ROOT);
            if (!filter.isEmpty() && !text.contains(filter)) continue;
            shown.add(track);
        }
        if (shown.isEmpty()) {
            TextView empty = text(library.isEmpty() ? "No music found on this device yet." : "Nothing matches this view.", 14, MUTED, false);
            empty.setPadding(dp(4), dp(20), dp(4), dp(20)); list.addView(empty, matchWrap()); return;
        }
        for (Track track : shown) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12), dp(9), dp(7), dp(9)); row.setBackground(roundRect(PANEL, 14));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, dp(66)); rowLp.bottomMargin = dp(7); list.addView(row, rowLp);
            TextView cover = text("♫", 21, ACCENT, true); cover.setGravity(Gravity.CENTER); cover.setBackground(roundRect(BG, 10)); row.addView(cover, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout names = new LinearLayout(this); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(11), 0, dp(3), 0);
            TextView title = text(track.title, 14, INK, true); TextView byline = text(track.artist + "  ·  " + track.album, 12, MUTED, false);
            names.addView(title, matchWrap()); names.addView(byline, matchWrap());
            row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            Button fav = button(favorites.contains(track.id) ? "♥" : "♡", PANEL, favorites.contains(track.id) ? ACCENT : MUTED);
            fav.setOnClickListener(v -> { if (!favorites.add(track.id)) favorites.remove(track.id); persistFavorites(); renderLibrary(); });
            row.addView(fav, new LinearLayout.LayoutParams(dp(42), dp(44)));
            row.setOnClickListener(v -> playFrom(track));
        }
    }

    private void connectPlayback() {
        SessionToken token = new SessionToken(this, new android.content.ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(new Player.Listener() {
                    @Override public void onIsPlayingChanged(boolean isPlaying) { updateNowPlaying(); }
                    @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) { updateNowPlaying(); }
                });
                updateNowPlaying();
            } catch (Exception ignored) { status.setText("Playback service could not start."); }
        }, mainExecutor);
    }

    private void playFrom(Track selected) {
        if (controller == null || library.isEmpty()) return;
        List<MediaItem> items = new ArrayList<>(); int index = 0;
        for (Track track : library) {
            if (track.id == selected.id) index = items.size();
            MediaMetadata metadata = new MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build();
            items.add(new MediaItem.Builder().setMediaId(Long.toString(track.id)).setUri(track.uri).setMediaMetadata(metadata).build());
        }
        controller.setMediaItems(items, index, 0); controller.prepare(); controller.play();
        updateNowPlaying();
    }

    private void togglePlayback() {
        if (controller == null) return;
        if (controller.getMediaItemCount() == 0) {
            if (!library.isEmpty()) playFrom(library.get(0));
        } else if (controller.isPlaying()) controller.pause(); else controller.play();
        updateNowPlaying();
    }

    private void updateNowPlaying() {
        if (nowPlaying == null || controller == null) return;
        MediaMetadata metadata = controller.getMediaMetadata();
        String title = metadata.title == null ? "Nothing playing" : metadata.title.toString();
        String artist = metadata.artist == null ? "" : "  ·  " + metadata.artist;
        nowPlaying.setText(title + artist);
        playButton.setText(controller.isPlaying() ? "Ⅱ" : "▶");
    }

    private void persistFavorites() {
        Set<String> values = new HashSet<>(); for (Long id : favorites) values.add(Long.toString(id));
        getPreferences(MODE_PRIVATE).edit().putStringSet("favorites", values).apply();
    }

    private String safe(String value, String fallback) { return value == null || value.trim().isEmpty() || value.equals("<unknown>") ? fallback : value; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD); return view;
    }
    private Button button(String label, int background, int foreground) {
        Button view = new Button(this); view.setText(label); view.setTextSize(11); view.setTextColor(foreground); view.setAllCaps(false);
        view.setPadding(dp(3), 0, dp(3), 0); view.setMinWidth(0); view.setMinimumWidth(0); view.setBackground(roundRect(background, 12)); return view;
    }
    private android.graphics.drawable.GradientDrawable roundRect(int color, int radius) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private int dp(int px) { return (int) (px * getResources().getDisplayMetrics().density + .5f); }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressTick);
        if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
        controller = null;
        super.onDestroy();
    }

    private static final class Track {
        final long id; final Uri uri; final String title, artist, album; final long albumId, duration;
        Track(long id, Uri uri, String title, String artist, String album, long albumId, long duration) {
            this.id = id; this.uri = uri; this.title = title; this.artist = artist; this.album = album; this.albumId = albumId; this.duration = duration;
        }
    }
}
