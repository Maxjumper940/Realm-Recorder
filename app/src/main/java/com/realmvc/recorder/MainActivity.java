package com.realmvc.recorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home / Videos / Settings, all built in code with the pink Realm VC theme. */
public class MainActivity extends Activity implements RecState.Listener {
    static final String EXTRA_TAB = "tab";
    static final int TAB_HOME = 0;
    static final int TAB_VIDEOS = 1;
    static final int TAB_SETTINGS = 2;
    private static final int REQ_NOTIF = 10;
    private static final int REQ_MEDIA = 11;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<Uri, Bitmap> thumbs = new HashMap<>();

    private FrameLayout content;
    private final TextView[] tabLabels = new TextView[3];
    private final ImageView[] tabIcons = new ImageView[3];
    private int tab = TAB_HOME;

    // Home widgets that change with state.
    private RecordButton recordButton;
    private TextView recordTimer;
    private TextView recordHint;
    private TextView storagePill;
    private LinearLayout recentBox;
    private TextView ballTileValue;
    private TextView audioTileValue;
    private TextView qualityTileValue;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateRecordUi();
            if (RecState.isRecording()) handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifs.ensureChannels(this);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildTabBar(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        int start = getIntent().getIntExtra(EXTRA_TAB, TAB_HOME);
        showTab(start);

        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra(EXTRA_TAB)) showTab(intent.getIntExtra(EXTRA_TAB, TAB_HOME));
    }

    @Override
    protected void onResume() {
        super.onResume();
        RecState.add(this);
        if (Prefs.floatingBall(this) && Settings.canDrawOverlays(this)) startBall();
        Notifs.showToolbar(this);
        showTab(tab); // refresh lists, storage and permission states
        handler.removeCallbacks(tick);
        if (RecState.isRecording()) handler.post(tick);
    }

    @Override
    protected void onPause() {
        RecState.remove(this);
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) Notifs.showToolbar(this);
        showTab(tab);
    }

    @Override public void onRecStateChanged() {
        updateRecordUi();
        handler.removeCallbacks(tick);
        if (RecState.isRecording()) handler.post(tick);
        else if ("saved".equals(RecState.lastMessage()) && tab != TAB_SETTINGS) showTab(tab);
    }

    // ---------------------------------------------------------------- tabs

    private View buildTabBar() {
        LinearLayout bar = Ui.row(this);
        bar.setBackground(Ui.round(0xFF0E0714, 0, Ui.STROKE, Ui.dp(this, 1)));
        bar.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        String[] names = {"Home", "Videos", "Tools"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_videos, R.drawable.ic_settings};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout item = Ui.column(this);
            item.setGravity(Gravity.CENTER);
            ImageView ic = Ui.icon(this, icons[i], Ui.MUTED, 24);
            TextView t = Ui.text(this, names[i], 12, Ui.MUTED, true);
            t.setGravity(Gravity.CENTER);
            item.addView(ic);
            item.addView(t);
            item.setBackground(Ui.ripple(null));
            Ui.clickable(item);
            item.setOnClickListener(v -> showTab(idx));
            item.setContentDescription(names[i]);
            tabIcons[i] = ic;
            tabLabels[i] = t;
            bar.addView(item, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        }
        return bar;
    }

    private void showTab(int t) {
        if (t < TAB_HOME || t > TAB_SETTINGS) t = TAB_HOME;
        tab = t;
        for (int i = 0; i < 3; i++) {
            int col = i == t ? Ui.PINK : Ui.MUTED;
            tabLabels[i].setTextColor(col);
            tabIcons[i].setImageTintList(android.content.res.ColorStateList.valueOf(col));
        }
        content.removeAllViews();
        recordButton = null;
        View page;
        if (t == TAB_VIDEOS) page = buildVideos();
        else if (t == TAB_SETTINGS) page = buildSettings();
        else page = buildHome();
        content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        updateRecordUi();
    }

    private ScrollView scroller(LinearLayout inner) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        int p = Ui.dp(this, 18);
        inner.setPadding(p, Ui.dp(this, 14), p, Ui.dp(this, 24));
        sv.addView(inner, new ScrollView.LayoutParams(-1, -2));
        return sv;
    }

    // ---------------------------------------------------------------- home

    private View buildHome() {
        FrameLayout frame = new FrameLayout(this);
        NeonGridView grid = new NeonGridView(this);
        grid.setHorizon(0.5f);
        grid.setAlpha(0.55f);
        frame.addView(grid, new FrameLayout.LayoutParams(-1, Ui.dp(this, 300)));

        LinearLayout col = Ui.column(this);

        // Header: brand + storage pill.
        LinearLayout head = Ui.row(this);
        LinearLayout brandCol = Ui.column(this);
        brandCol.addView(Ui.brand(this, "Realm VC", 34));
        TextView tag = Ui.text(this, "Screen + the voices", 14, Ui.LAVENDER, false);
        tag.setPadding(Ui.dp(this, 6), 0, 0, 0);
        brandCol.addView(tag);
        head.addView(brandCol, new LinearLayout.LayoutParams(0, -2, 1f));
        storagePill = Ui.pill(this, "");
        head.addView(storagePill);
        col.addView(head);
        updateStorage();

        // Description card.
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.gradient(new int[]{0xE62A0B3A, 0xE6140A1C}, Ui.dp(this, 22),
                GradientDrawable.Orientation.TL_BR));
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        card.addView(Ui.text(this, "Record your Realm with the voices", 18, Ui.TEXT, true));
        TextView desc = Ui.text(this,
                "Realm Recorder captures your screen together with your friends' voices from a Realm VC call "
                        + "and the game sound from Minecraft, then saves an HD MP4 to Movies/RealmRecorder, "
                        + "ready to edit into videos.", 14, Ui.MUTED, false);
        desc.setLineSpacing(0, 1.2f);
        card.addView(desc, Ui.lpMargins(this, -1, -2, 0, 6, 0, 10));
        LinearLayout chips = Ui.row(this);
        chips.addView(Ui.pill(this, "Realm VC voices"));
        chips.addView(space(8));
        chips.addView(Ui.pill(this, "Minecraft sound"));
        chips.addView(space(8));
        chips.addView(Ui.pill(this, "HD screen"));
        card.addView(chips);
        col.addView(card, Ui.lpMargins(this, -1, -2, 0, 16, 0, 0));

        // Big record button.
        LinearLayout recBox = Ui.column(this);
        recBox.setGravity(Gravity.CENTER_HORIZONTAL);
        recordButton = new RecordButton(this);
        recordButton.setOnClickListener(v -> onRecordPressed());
        recBox.addView(recordButton, new LinearLayout.LayoutParams(Ui.dp(this, 170), Ui.dp(this, 170)));
        recordTimer = Ui.text(this, "Tap to record", 20, Ui.TEXT, true);
        recordTimer.setGravity(Gravity.CENTER);
        recBox.addView(recordTimer);
        recordHint = Ui.text(this, "", 13, Ui.MUTED, false);
        recordHint.setGravity(Gravity.CENTER);
        recBox.addView(recordHint, Ui.lpMargins(this, -2, -2, 0, 2, 0, 0));
        col.addView(recBox, Ui.lpMargins(this, -1, -2, 0, 14, 0, 6));

        // Tool row.
        LinearLayout tools = Ui.row(this);
        tools.addView(tile(R.drawable.ic_shot, "Screenshot", "One tap", v -> openProjection(ProjectionActivity.MODE_SHOT), null),
                new LinearLayout.LayoutParams(0, -2, 1f));
        tools.addView(space(8));
        tools.addView(tile(R.drawable.ic_ball, "Floating ball", "", v -> toggleBall(), t -> ballTileValue = t),
                new LinearLayout.LayoutParams(0, -2, 1f));
        tools.addView(space(8));
        tools.addView(tile(R.drawable.ic_mic, "Audio", "", v -> chooseAudio(), t -> audioTileValue = t),
                new LinearLayout.LayoutParams(0, -2, 1f));
        tools.addView(space(8));
        tools.addView(tile(R.drawable.ic_hd, "Quality", "", v -> chooseQuality(), t -> qualityTileValue = t),
                new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(tools, Ui.lpMargins(this, -1, -2, 0, 10, 0, 0));
        refreshTileValues();

        // Realm VC tip.
        LinearLayout tip = Ui.column(this);
        tip.setBackground(Ui.card(this));
        tip.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        tip.addView(Ui.text(this, "Getting the call voices in", 15, Ui.PINK, true));
        TextView tipText = Ui.text(this,
                "Android keeps normal call audio private. In Realm VC open Me and turn on Recording mode "
                        + "before you join, and wear headphones. Your friends' voices and the Minecraft sound then go "
                        + "into the video. Android lets only one app use the mic, so during a call Realm Recorder leaves "
                        + "it to Realm VC and your own voice isn't recorded.",
                13, Ui.MUTED, false);
        tipText.setLineSpacing(0, 1.15f);
        tip.addView(tipText, Ui.lpMargins(this, -1, -2, 0, 4, 0, 10));
        LinearLayout tipBtns = Ui.row(this);
        TextView openVc = Ui.button(this, "Open Realm VC", true);
        openVc.setOnClickListener(v -> launchPackage(Prefs.PKG_REALMVC, "Realm VC"));
        tipBtns.addView(openVc);
        tipBtns.addView(space(8));
        TextView openMc = Ui.button(this, "Open Minecraft", false);
        openMc.setOnClickListener(v -> launchPackage(Prefs.PKG_MINECRAFT, "Minecraft"));
        tipBtns.addView(openMc);
        tip.addView(tipBtns);
        col.addView(tip, Ui.lpMargins(this, -1, -2, 0, 16, 0, 0));

        // Recent recordings.
        LinearLayout sec = Ui.row(this);
        sec.addView(Ui.text(this, "Recent recordings", 17, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView all = Ui.text(this, "See all \u203A", 14, Ui.PINK, true);
        all.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), 0, Ui.dp(this, 6));
        all.setOnClickListener(v -> showTab(TAB_VIDEOS));
        sec.addView(all);
        col.addView(sec, Ui.lpMargins(this, -1, -2, 0, 20, 0, 6));
        recentBox = Ui.column(this);
        col.addView(recentBox);
        loadRecordings(recentBox, 3);

        frame.addView(scroller(col), new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private interface TextSink { void take(TextView t); }

    private View tile(int icon, String title, String value, View.OnClickListener l, TextSink sink) {
        LinearLayout t = Ui.column(this);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 10));
        t.setBackground(Ui.ripple(Ui.card(this)));
        Ui.clickable(t);
        t.setOnClickListener(l);
        ImageView ic = Ui.icon(this, icon, Ui.PINK, 26);
        t.addView(ic);
        TextView tt = Ui.text(this, title, 12, Ui.TEXT, true);
        tt.setGravity(Gravity.CENTER);
        tt.setMaxLines(1);
        t.addView(tt, Ui.lpMargins(this, -2, -2, 0, 6, 0, 0));
        TextView vv = Ui.text(this, value, 10, Ui.MUTED, false);
        vv.setGravity(Gravity.CENTER);
        vv.setMaxLines(2);
        t.addView(vv);
        if (sink != null) sink.take(vv);
        t.setContentDescription(title);
        return t;
    }

    private View space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, dp), Ui.dp(this, dp)));
        return v;
    }

    private void refreshTileValues() {
        if (ballTileValue != null) ballTileValue.setText(Prefs.floatingBall(this) && Settings.canDrawOverlays(this) ? "On" : "Off");
        if (audioTileValue != null) {
            String m = Prefs.audioMode(this);
            audioTileValue.setText(Prefs.audioUsesInternal(m)
                    ? Prefs.audioModeLabel(m) + "\n" + shortTarget(Prefs.audioTarget(this))
                    : Prefs.audioModeLabel(m));
        }
        if (qualityTileValue != null) {
            qualityTileValue.setText(Prefs.resolution(this) + "p " + Prefs.fps(this) + "fps\n" + Prefs.bitrateMbps(this) + " Mbps");
        }
        if (recordHint != null) recordHint.setText(summary());
    }

    private static String shortTarget(String t) {
        switch (t) {
            case Prefs.TARGET_REALMVC: return "Realm VC";
            case Prefs.TARGET_MINECRAFT: return "Minecraft";
            default: return "All apps";
        }
    }

    private String summary() {
        String m = Prefs.audioMode(this);
        String s = Prefs.audioModeLabel(m);
        if (Prefs.audioUsesInternal(m)) s += " \u00B7 " + Prefs.targetLabel(Prefs.audioTarget(this));
        return s + " \u00B7 " + Prefs.resolution(this) + "p " + Prefs.fps(this) + "fps";
    }

    private void updateStorage() {
        if (storagePill == null) return;
        storagePill.setText(Media.human(Media.freeBytes()) + " free");
    }

    private void updateRecordUi() {
        if (recordButton == null) return;
        boolean rec = RecState.isRecording();
        recordButton.setRecording(rec);
        recordTimer.setText(rec ? "\u25CF " + RecState.format(RecState.elapsedMs()) : "Tap to record");
        recordTimer.setTextColor(rec ? Ui.PINK : Ui.TEXT);
        recordHint.setText(rec ? "Recording \u2014 stop here, from the shade or the floating ball" : summary());
    }

    private void onRecordPressed() {
        if (RecState.isRecording()) {
            RecorderService.sendAction(this, RecorderService.ACTION_STOP);
            return;
        }
        if (Media.freeBytes() >= 0 && Media.freeBytes() < 300L * 1024 * 1024) {
            Toast.makeText(this, "Less than 300 MB free. Free up space before recording.", Toast.LENGTH_LONG).show();
            return;
        }
        openProjection(ProjectionActivity.MODE_RECORD);
    }

    private void openProjection(String mode) {
        startActivity(new Intent(this, ProjectionActivity.class).putExtra(ProjectionActivity.EXTRA_MODE, mode));
        overridePendingTransition(0, 0);
    }

    private void launchPackage(String pkg, String label) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, label + " isn't installed", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(i);
    }

    // ------------------------------------------------------------- dialogs

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private void chooseAudio() {
        final String[] modes = {Prefs.AUDIO_BOTH, Prefs.AUDIO_INTERNAL, Prefs.AUDIO_MIC, Prefs.AUDIO_MUTE};
        String[] labels = {
                "Internal + Mic (app sound + your voice)",
                "Internal audio (app sound only)",
                "Microphone only",
                "Mute (no sound)"};
        int sel = indexOf(modes, Prefs.audioMode(this));
        dialog().setTitle("Audio source")
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    Prefs.setAudioMode(this, modes[which]);
                    d.dismiss();
                    if (Prefs.audioUsesInternal(modes[which])) chooseTarget();
                    else afterSettingChange();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseTarget() {
        final String[] targets = {Prefs.TARGET_ALL, Prefs.TARGET_REALMVC, Prefs.TARGET_MINECRAFT};
        String[] labels = {
                "All compatible apps (Realm VC + Minecraft + others)",
                "Realm VC only" + (RecorderService.uidOf(this, Prefs.PKG_REALMVC) < 0 ? " (not installed)" : ""),
                "Minecraft only" + (RecorderService.uidOf(this, Prefs.PKG_MINECRAFT) < 0 ? " (not installed)" : "")};
        dialog().setTitle("Capture audio from")
                .setSingleChoiceItems(labels, indexOf(targets, Prefs.audioTarget(this)), (d, which) -> {
                    Prefs.setAudioTarget(this, targets[which]);
                    d.dismiss();
                    afterSettingChange();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseQuality() {
        final int[][] presets = {{720, 30, 5}, {720, 60, 8}, {1080, 30, 8}, {1080, 60, 12}, {1080, 60, 16}};
        String[] labels = {"720p \u00B7 30fps \u00B7 5 Mbps", "720p \u00B7 60fps \u00B7 8 Mbps",
                "1080p \u00B7 30fps \u00B7 8 Mbps", "1080p \u00B7 60fps \u00B7 12 Mbps", "1080p \u00B7 60fps \u00B7 16 Mbps (best)"};
        int sel = -1;
        for (int i = 0; i < presets.length; i++) {
            if (presets[i][0] == Prefs.resolution(this) && presets[i][1] == Prefs.fps(this)
                    && presets[i][2] == Prefs.bitrateMbps(this)) sel = i;
        }
        dialog().setTitle("Video quality")
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    Prefs.setResolution(this, presets[which][0]);
                    Prefs.setFps(this, presets[which][1]);
                    Prefs.setBitrateMbps(this, presets[which][2]);
                    d.dismiss();
                    afterSettingChange();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseInt(String title, int[] values, String suffix, int current, IntSink sink) {
        String[] labels = new String[values.length];
        int sel = -1;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + suffix;
            if (values[i] == current) sel = i;
        }
        dialog().setTitle(title)
                .setSingleChoiceItems(labels, sel, (d, which) -> {
                    sink.take(values[which]);
                    d.dismiss();
                    afterSettingChange();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private interface IntSink { void take(int v); }

    private void chooseOrientation() {
        final String[] vals = {Prefs.ORIENT_AUTO, Prefs.ORIENT_LANDSCAPE, Prefs.ORIENT_PORTRAIT};
        String[] labels = {"Auto (screen's current way)", "Landscape (best for Minecraft)", "Portrait"};
        dialog().setTitle("Video orientation")
                .setSingleChoiceItems(labels, indexOf(vals, Prefs.orientation(this)), (d, which) -> {
                    Prefs.setOrientation(this, vals[which]);
                    d.dismiss();
                    afterSettingChange();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return 0;
    }

    private void afterSettingChange() {
        refreshTileValues();
        Notifs.showToolbar(this);
        if (tab == TAB_SETTINGS) showTab(TAB_SETTINGS);
    }

    // -------------------------------------------------------- floating ball

    private void toggleBall() {
        boolean on = !(Prefs.floatingBall(this) && Settings.canDrawOverlays(this));
        if (on && !Settings.canDrawOverlays(this)) {
            dialog().setTitle("Allow \u201CDisplay over other apps\u201D")
                    .setMessage("The floating ball sits on top of Minecraft so you can start, stop and screenshot without leaving the game. "
                            + "On the next screen, find Realm Recorder and turn on \u201CAllow display over other apps\u201D "
                            + "(some phones call it \u201CAppear on top\u201D).")
                    .setPositiveButton("Open settings", (d, w) -> {
                        Prefs.setFloatingBall(this, true);
                        openOverlaySettings();
                    })
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }
        Prefs.setFloatingBall(this, on);
        if (on) startBall();
        else stopService(new Intent(this, FloatingBallService.class));
        refreshTileValues();
        if (tab == TAB_SETTINGS) showTab(TAB_SETTINGS);
        Toast.makeText(this, on ? "Floating ball on" : "Floating ball off", Toast.LENGTH_SHORT).show();
    }

    private void openOverlaySettings() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException ignored) {}
    }

    private void startBall() {
        try {
            startService(new Intent(this, FloatingBallService.class));
        } catch (RuntimeException ignored) {}
    }

    // -------------------------------------------------------------- videos

    private View buildVideos() {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.brand(this, "Videos", 30));
        col.addView(Ui.text(this, "Saved in Movies/RealmRecorder. Tap to play, or share straight to your editor.",
                13, Ui.MUTED, false), Ui.lpMargins(this, -1, -2, 6, 2, 0, 12));
        if (!hasMediaRead()) {
            TextView allow = Ui.button(this, "Show videos from older installs", false);
            allow.setOnClickListener(v -> requestMediaRead());
            col.addView(allow, Ui.lpMargins(this, -2, -2, 0, 0, 0, 12));
        }
        LinearLayout list = Ui.column(this);
        col.addView(list);
        loadRecordings(list, 200);
        return scroller(col);
    }

    private boolean hasMediaRead() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaRead() {
        String p = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
        requestPermissions(new String[]{p}, REQ_MEDIA);
    }

    private void loadRecordings(LinearLayout into, int limit) {
        TextView loading = Ui.text(this, "Loading\u2026", 13, Ui.MUTED, false);
        into.addView(loading);
        io.execute(() -> {
            List<Media.Recording> recs = Media.listRecordings(this, limit);
            handler.post(() -> {
                if (isDestroyed()) return;
                into.removeAllViews();
                if (recs.isEmpty()) {
                    into.addView(emptyState());
                    return;
                }
                for (Media.Recording r : recs) into.addView(recordingRow(r));
            });
        });
    }

    private View emptyState() {
        LinearLayout e = Ui.column(this);
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        e.setBackground(Ui.card(this));
        e.setPadding(Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22));
        e.addView(Ui.icon(this, R.drawable.ic_videos, Ui.PINK, 34));
        TextView t = Ui.text(this, "No recordings yet", 15, Ui.TEXT, true);
        e.addView(t, Ui.lpMargins(this, -2, -2, 0, 8, 0, 2));
        TextView s = Ui.text(this, "Tap the pink button on Home to record your first Realm session.", 13, Ui.MUTED, false);
        s.setGravity(Gravity.CENTER);
        e.addView(s);
        return e;
    }

    private View recordingRow(Media.Recording r) {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.ripple(Ui.card(this)));
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        Ui.clickable(row);
        row.setOnClickListener(v -> play(r.uri));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.bottomMargin = Ui.dp(this, 10);
        row.setLayoutParams(rlp);

        FrameLayout thumbBox = new FrameLayout(this);
        thumbBox.setBackground(Ui.round(Ui.SURFACE2, Ui.dp(this, 12), 0, 0));
        thumbBox.setClipToOutline(true);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        ImageView play = new ImageView(this);
        play.setImageResource(R.drawable.ic_play);
        play.setImageTintList(android.content.res.ColorStateList.valueOf(0xEEFFFFFF));
        thumbBox.addView(play, new FrameLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28), Gravity.CENTER));
        row.addView(thumbBox, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 60)));
        loadThumb(r.uri, thumb);

        LinearLayout info = Ui.column(this);
        info.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), 0);
        TextView name = Ui.text(this, r.name, 14, Ui.TEXT, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        info.addView(name);
        String meta = (r.durationMs > 0 ? RecState.format(r.durationMs) + " \u00B7 " : "")
                + Media.human(r.sizeBytes) + " \u00B7 "
                + DateUtils.getRelativeTimeSpanString(r.dateSec * 1000L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
        info.addView(Ui.text(this, meta, 12, Ui.MUTED, false));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView share = Ui.icon(this, R.drawable.ic_share, Ui.PINK, 40);
        share.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        share.setBackground(Ui.ripple(null));
        share.setContentDescription("Share");
        Ui.clickable(share);
        share.setOnClickListener(v -> share(r.uri));
        row.addView(share);
        return row;
    }

    private void loadThumb(Uri uri, ImageView into) {
        Bitmap cached = thumbs.get(uri);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        io.execute(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(uri, new Size(320, 200), null);
                handler.post(() -> {
                    thumbs.put(uri, b);
                    into.setImageBitmap(b);
                });
            } catch (Exception ignored) {
                // No thumbnail yet (file still being indexed).
            }
        });
    }

    private void play(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show();
        }
    }

    private void share(Uri uri) {
        Intent s = new Intent(Intent.ACTION_SEND).setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(s, "Share recording"));
        } catch (ActivityNotFoundException ignored) {}
    }

    // ------------------------------------------------------------ settings

    private View buildSettings() {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.brand(this, "Tools", 30));
        col.addView(Ui.text(this, "Audio, video quality, controls and permissions.", 13, Ui.MUTED, false),
                Ui.lpMargins(this, -1, -2, 6, 2, 0, 8));

        col.addView(section("Audio"));
        LinearLayout audio = group();
        audio.addView(item("Audio source", Prefs.audioModeLabel(Prefs.audioMode(this)), v -> chooseAudio()));
        audio.addView(item("Capture audio from", Prefs.targetLabel(Prefs.audioTarget(this)), v -> chooseTarget()));
        col.addView(audio);

        col.addView(section("Video"));
        LinearLayout video = group();
        video.addView(item("Resolution", Prefs.resolution(this) + "p", v ->
                chooseInt("Resolution", new int[]{720, 1080}, "p", Prefs.resolution(this), x -> Prefs.setResolution(this, x))));
        video.addView(item("Frame rate", Prefs.fps(this) + " fps", v ->
                chooseInt("Frame rate", new int[]{30, 60}, " fps", Prefs.fps(this), x -> Prefs.setFps(this, x))));
        video.addView(item("Bitrate", Prefs.bitrateMbps(this) + " Mbps", v ->
                chooseInt("Bitrate", new int[]{4, 6, 8, 12, 16, 20}, " Mbps", Prefs.bitrateMbps(this), x -> Prefs.setBitrateMbps(this, x))));
        video.addView(item("Orientation", Prefs.orientationLabel(Prefs.orientation(this)), v -> chooseOrientation()));
        col.addView(video);

        col.addView(section("Controls"));
        LinearLayout controls = group();
        controls.addView(switchItem("Floating ball", "Record, screenshot, Home and Exit over any app",
                Prefs.floatingBall(this) && Settings.canDrawOverlays(this), on -> toggleBall()));
        controls.addView(switchItem("Notification toolbar", "Record / Screenshot / Tools / Home / Exit in the shade",
                Prefs.toolbarNotification(this), on -> {
                    Prefs.setToolbarNotification(this, on);
                    if (on) Notifs.showToolbar(this); else Notifs.hideToolbar(this);
                }));
        col.addView(controls);

        col.addView(section("Permissions"));
        LinearLayout perms = group();
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        perms.addView(item("Microphone & internal audio", mic ? "Allowed" : "Tap to allow", v -> {
            if (!mic) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_NOTIF);
        }));
        boolean notif = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        perms.addView(item("Notifications", notif ? "Allowed" : "Tap to allow", v -> {
            if (!notif && Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }));
        boolean overlay = Settings.canDrawOverlays(this);
        perms.addView(item("Display over other apps", overlay ? "Allowed" : "Tap to open settings", v -> {
            if (!overlay) openOverlaySettings();
        }));
        perms.addView(item("App settings", "Open Android settings for Realm Recorder", v -> openAppSettings()));
        col.addView(perms);

        col.addView(section("What gets recorded"));
        LinearLayout about = group();
        TextView a = Ui.text(this,
                "\u2022 Screen: the whole screen (or the single app you pick on Android 14+).\n"
                        + "\u2022 Minecraft: game sound is recorded with Internal audio.\n"
                        + "\u2022 Realm VC: turn on Recording mode in Realm VC (Me tab) before joining so the call voices "
                        + "play as media audio Android lets recorders capture. Use headphones.\n"
                        + "\u2022 Without Recording mode, Android hides call audio from every recorder.\n"
                        + "\u2022 Only one app can use the mic at a time. If Realm VC (or another app) is already using it "
                        + "when you press Record, Realm Recorder skips the mic so your call keeps working.\n"
                        + "\u2022 Some apps block capture on purpose; Android always respects that.\n"
                        + "\u2022 Tell everyone in the call that you're recording.",
                13, Ui.MUTED, false);
        a.setLineSpacing(0, 1.2f);
        a.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        about.addView(a);
        col.addView(about);

        TextView ver = Ui.text(this, "Realm Recorder " + versionName(), 12, Ui.MUTED, false);
        ver.setGravity(Gravity.CENTER);
        col.addView(ver, Ui.lpMargins(this, -1, -2, 0, 16, 0, 0));
        return scroller(col);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private TextView section(String s) {
        TextView t = Ui.text(this, s.toUpperCase(java.util.Locale.US), 12, Ui.PINK, true);
        t.setLetterSpacing(0.12f);
        t.setPadding(Ui.dp(this, 6), Ui.dp(this, 16), 0, Ui.dp(this, 6));
        return t;
    }

    private LinearLayout group() {
        LinearLayout g = Ui.column(this);
        g.setBackground(Ui.card(this));
        return g;
    }

    private View item(String title, String value, View.OnClickListener l) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        r.setBackground(Ui.ripple(null));
        Ui.clickable(r);
        r.setOnClickListener(l);
        r.addView(Ui.text(this, title, 15, Ui.TEXT, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = Ui.text(this, value + "  \u203A", 13, Ui.LAVENDER, false);
        v.setGravity(Gravity.END);
        r.addView(v, new LinearLayout.LayoutParams(-2, -2));
        r.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        return r;
    }

    private interface BoolSink { void take(boolean on); }

    private View switchItem(String title, String sub, boolean checked, BoolSink sink) {
        LinearLayout r = Ui.row(this);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        LinearLayout texts = Ui.column(this);
        texts.addView(Ui.text(this, title, 15, Ui.TEXT, false));
        texts.addView(Ui.text(this, sub, 12, Ui.MUTED, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.PINK));
        sw.setOnCheckedChangeListener((b, on) -> sink.take(on));
        r.addView(sw);
        return r;
    }
}
