package com.realmvc.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.media.projection.MediaProjectionConfig;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQUEST_SCREEN_CAPTURE = 11;
    private static final int REQUEST_MICROPHONE = 12;
    private static final int REQUEST_NOTIFICATIONS = 13;

    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private Button shareButton;
    private Spinner screenCaptureSpinner;
    private Spinner audioAppSpinner;
    private final List<AudioApp> audioApps = new ArrayList<>();
    private Uri lastRecording;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(ScreenRecorderService.EXTRA_STATUS);
            if (status != null) statusView.setText(status);
            String uri = intent.getStringExtra(ScreenRecorderService.EXTRA_RECORDING_URI);
            if (uri != null) {
                lastRecording = Uri.parse(uri);
                shareButton.setEnabled(true);
            }
            String error = intent.getStringExtra(ScreenRecorderService.EXTRA_ERROR);
            if (error != null) statusView.setText(error);
            boolean recording = intent.getBooleanExtra(ScreenRecorderService.EXTRA_RECORDING, false);
            startButton.setEnabled(!recording);
            stopButton.setEnabled(recording);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        IntentFilter filter = new IntentFilter(ScreenRecorderService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    private void buildScreen() {
        loadAudioApps();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(28), dp(22), dp(28));
        page.setBackgroundColor(0xff100914);
        scroll.addView(page);

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = new TextView(this);
        mark.setText("R");
        mark.setTextColor(0xffffffff);
        mark.setTextSize(22);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(circleBackground(0xffff4fa8));
        brand.addView(mark, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        brandText.setPadding(dp(12), 0, 0, 0);
        TextView title = label("Realm Recorder", 23, 0xffffffff, true);
        TextView subtitle = label("SCREEN + CALL AUDIO", 11, 0xffff8fc4, true);
        brandText.addView(title, matchWrap());
        brandText.addView(subtitle, matchWrap());
        brand.addView(brandText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(brand, matchWrap());

        TextView introduction = label(
                "Record your gameplay and voice call together. Recordings stay on this phone.",
                16, 0xffffc9e2, false);
        introduction.setPadding(0, dp(22), 0, dp(18));
        page.addView(introduction, matchWrap());

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(18), dp(18), dp(18), dp(18));
        options.setBackground(cardBackground());

        TextView sectionTitle = label("RECORDING SETUP", 12, 0xffff72b6, true);
        sectionTitle.setLetterSpacing(0.12f);
        options.addView(sectionTitle, matchWrap());

        options.addView(fieldLabel("Screen"), fieldLayout());
        List<String> screenModes = new ArrayList<>();
        screenModes.add("Entire screen");
        if (Build.VERSION.SDK_INT >= 34) screenModes.add("Choose one app (Android 14+)");
        screenCaptureSpinner = textSpinner(screenModes);
        options.addView(screenCaptureSpinner, fieldLayout());
        TextView screenHelp = label(
                Build.VERSION.SDK_INT >= 34
                        ? "Choose one app to open Android's app-capture picker."
                        : "Android 14+ can capture one app; older versions record the full screen.",
                12, 0xffc7afc1, false);
        options.addView(screenHelp, helperLayout());

        options.addView(fieldLabel("App audio"), fieldLayout());
        audioAppSpinner = new Spinner(this);
        audioAppSpinner.setAdapter(new AudioAppAdapter());
        audioAppSpinner.setBackground(selectionBackground());
        options.addView(audioAppSpinner, fieldLayout());
        TextView audioHelp = label(
                "Choose which app's audio to include. “All compatible app audio” is best for Minecraft plus Realm VC.",
                12, 0xffc7afc1, false);
        options.addView(audioHelp, helperLayout());
        page.addView(options, matchWrap());

        LinearLayout notice = new LinearLayout(this);
        notice.setOrientation(LinearLayout.VERTICAL);
        notice.setPadding(dp(16), dp(14), dp(16), dp(14));
        notice.setBackground(softCardBackground());
        TextView noticeTitle = label("A quick note", 14, 0xffff8fc4, true);
        TextView noticeBody = label(
                "Android asks for screen permission each time. Some apps block audio capture, especially protected call audio. Tell everyone before recording and get their consent.",
                13, 0xffffd6e8, false);
        noticeBody.setPadding(0, dp(6), 0, 0);
        notice.addView(noticeTitle, matchWrap());
        notice.addView(noticeBody, matchWrap());
        LinearLayout.LayoutParams noticeParams = matchWrap();
        noticeParams.topMargin = dp(14);
        page.addView(notice, noticeParams);

        statusView = label("Ready when you are.", 14, 0xffd9c5d5, false);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(20), 0, dp(12));
        page.addView(statusView, matchWrap());

        startButton = button("Start recording", 0xffff4fa8, true);
        startButton.setOnClickListener(view -> requestPermissionsAndStart());
        LinearLayout.LayoutParams primaryParams = buttonLayout();
        primaryParams.topMargin = dp(4);
        page.addView(startButton, primaryParams);

        stopButton = button("Stop recording", 0xff321728, false);
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(view -> {
            Intent stop = new Intent(this, ScreenRecorderService.class);
            stop.setAction(ScreenRecorderService.ACTION_STOP);
            startService(stop);
            statusView.setText("Saving recording…");
            stopButton.setEnabled(false);
        });
        page.addView(stopButton, buttonLayout());

        shareButton = button("Share last recording", 0xff1a101b, false);
        shareButton.setEnabled(false);
        shareButton.setOnClickListener(view -> shareRecording());
        page.addView(shareButton, buttonLayout());

        setContentView(scroll);
    }

    private void loadAudioApps() {
        audioApps.clear();
        audioApps.add(new AudioApp("All compatible app audio", null, -1,
                getDrawable(android.R.drawable.ic_media_play)));
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchableApps = getPackageManager().queryIntentActivities(
                launcherIntent, PackageManager.MATCH_DEFAULT_ONLY);
        Map<String, AudioApp> uniqueApps = new HashMap<>();
        for (ResolveInfo resolveInfo : launchableApps) {
            if (resolveInfo.activityInfo == null || resolveInfo.activityInfo.applicationInfo == null) continue;
            String packageName = resolveInfo.activityInfo.packageName;
            if (getPackageName().equals(packageName) || uniqueApps.containsKey(packageName)) continue;
            CharSequence appLabel = resolveInfo.loadLabel(getPackageManager());
            AudioApp app = new AudioApp(
                    appLabel == null ? packageName : appLabel.toString(),
                    packageName,
                    resolveInfo.activityInfo.applicationInfo.uid,
                    resolveInfo.loadIcon(getPackageManager()));
            uniqueApps.put(packageName, app);
        }
        List<AudioApp> installedApps = new ArrayList<>(uniqueApps.values());
        Collections.sort(installedApps, (left, right) -> left.label.compareToIgnoreCase(right.label));
        audioApps.addAll(installedApps);
    }

    private Spinner textSpinner(List<String> choices) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, choices) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(0xffffffff);
                view.setPadding(dp(14), dp(12), dp(14), dp(12));
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(0xffffffff);
                view.setBackgroundColor(0xff211321);
                view.setPadding(dp(14), dp(12), dp(14), dp(12));
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(selectionBackground());
        return spinner;
    }

    private TextView fieldLabel(String text) {
        return label(text, 14, 0xffffe8f3, true);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String text, int color, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(0xffffffff);
        button.setMinHeight(dp(52));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable background = new GradientDrawable(
                primary
                        ? GradientDrawable.Orientation.TL_BR
                        : GradientDrawable.Orientation.LEFT_RIGHT,
                primary ? new int[]{0xffff4fa8, 0xff8b2bff} : new int[]{color, color});
        background.setCornerRadius(dp(18));
        if (!primary) background.setStroke(dp(1), 0xff56304c);
        button.setBackground(background);
        return button;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff180f1b);
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), 0xff4a2940);
        return background;
    }

    private GradientDrawable softCardBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff211321);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0xff42223b);
        return background;
    }

    private GradientDrawable selectionBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff211321);
        background.setCornerRadius(dp(14));
        background.setStroke(dp(1), 0xff70405e);
        return background;
    }

    private GradientDrawable circleBackground(int color) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setShape(GradientDrawable.OVAL);
        return background;
    }

    private void requestPermissionsAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        statusView.setText("Waiting for Android screen-capture approval…");
        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= 34 && screenCaptureSpinner.getSelectedItemPosition() == 1) {
            captureIntent = manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForUserChoice());
        } else {
            captureIntent = manager.createScreenCaptureIntent();
        }
        startActivityForResult(captureIntent, REQUEST_SCREEN_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE || requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestPermissionsAndStart();
            } else {
                statusView.setText("Microphone and notification permissions are needed to record.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SCREEN_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            statusView.setText("Screen recording was not approved.");
            return;
        }
        Intent start = new Intent(this, ScreenRecorderService.class);
        start.putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode);
        start.putExtra(ScreenRecorderService.EXTRA_PROJECTION_DATA, data);
        AudioApp audioApp = (AudioApp) audioAppSpinner.getSelectedItem();
        if (audioApp != null) {
            start.putExtra(ScreenRecorderService.EXTRA_AUDIO_UID, audioApp.uid);
            start.putExtra(ScreenRecorderService.EXTRA_AUDIO_LABEL, audioApp.label);
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(start);
        else startService(start);
        statusView.setText("Starting screen and audio recording…");
    }

    private void shareRecording() {
        if (lastRecording == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("video/mp4");
        send.putExtra(Intent.EXTRA_STREAM, lastRecording);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share recording"));
    }

    private LinearLayout.LayoutParams buttonLayout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(10);
        return params;
    }

    private LinearLayout.LayoutParams fieldLayout() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        return params;
    }

    private LinearLayout.LayoutParams helperLayout() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(6);
        params.bottomMargin = dp(12);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class AudioApp {
        final String label;
        final String packageName;
        final int uid;
        final Drawable icon;

        AudioApp(String label, String packageName, int uid, Drawable icon) {
            this.label = label;
            this.packageName = packageName;
            this.uid = uid;
            this.icon = icon;
        }
    }

    private final class AudioAppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return audioApps.size();
        }

        @Override
        public AudioApp getItem(int position) {
            return audioApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return appOptionView(getItem(position), convertView, false);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return appOptionView(getItem(position), convertView, true);
        }

        private View appOptionView(AudioApp app, View convertView, boolean dropdown) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(8), dp(14), dp(8));
                ImageView icon = new ImageView(MainActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
                TextView name = label("", 14, 0xffffffff, false);
                LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                nameParams.leftMargin = dp(10);
                row.addView(name, nameParams);
            }
            ImageView icon = (ImageView) row.getChildAt(0);
            TextView name = (TextView) row.getChildAt(1);
            icon.setImageDrawable(app.icon);
            name.setText(app.label);
            name.setTextColor(0xffffffff);
            row.setBackgroundColor(dropdown ? 0xff211321 : 0x00000000);
            return row;
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(statusReceiver);
        super.onDestroy();
    }
}
