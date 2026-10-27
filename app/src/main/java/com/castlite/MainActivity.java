package com.castlite;

import android.Manifest;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.DigitsKeyListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Phone: pick the TV and start casting.
 * Android TV: opens the receiver screen straight away.
 */
public class MainActivity extends Activity {
    private EditText ip;
    private TextView info;
    private Button start;
    private final Handler hd = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            hd.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            startActivity(new Intent(this, ReceiverActivity.class));
            finish();
            return;
        }

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(48, 48, 48, 48);

        info = new TextView(this);
        info.setTextSize(16);
        info.setPadding(0, 0, 0, 32);

        ip = new EditText(this);
        ip.setHint("TV address, e.g. 192.168.43.20");
        ip.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        ip.setText(getSharedPreferences("p", 0).getString("ip", ""));

        Button find = new Button(this);
        find.setText("FIND TV");
        start = new Button(this);
        Button rx = new Button(this);
        rx.setText("USE THIS DEVICE AS THE TV SCREEN");

        l.addView(info);
        l.addView(ip);
        l.addView(find);
        l.addView(start);
        l.addView(rx);
        setContentView(l);

        find.setOnClickListener(v -> {
            info.setText("Searching for the TV...");
            new Thread(() -> {
                final String r = P.discover();
                runOnUiThread(() -> {
                    if (r != null) {
                        ip.setText(r);
                        info.setText("Found TV: " + r + "\nNow tap START CASTING.");
                    } else {
                        info.setText("TV not found. Open CastLite on the TV first, "
                                + "or type the address shown on the TV.");
                    }
                });
            }).start();
        });

        start.setOnClickListener(v -> {
            if (SendService.running) {
                stopService(new Intent(this, SendService.class));
                return;
            }
            String a = ip.getText().toString().trim();
            if (a.isEmpty()) {
                info.setText("Tap FIND TV or type the TV address first.");
                return;
            }
            getSharedPreferences("p", 0).edit().putString("ip", a).apply();
            if (Build.VERSION.SDK_INT >= 23
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(Build.VERSION.SDK_INT >= 33
                        ? new String[]{Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}
                        : new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            } else {
                capture();
            }
        });

        rx.setOnClickListener(v -> startActivity(new Intent(this, ReceiverActivity.class)));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        capture(); // without the audio permission it still casts video
    }

    private void capture() {
        MediaProjectionManager m =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), 1);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && res == RESULT_OK) {
            Intent i = new Intent(this, SendService.class)
                    .putExtra("code", res)
                    .putExtra("data", data)
                    .putExtra("ip", ip.getText().toString().trim());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (info != null) hd.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        hd.removeCallbacks(tick);
    }

    private void refresh() {
        if (info == null) return;
        start.setText(SendService.running ? "STOP CASTING" : "START CASTING");
        String st = SendService.status;
        if (!st.isEmpty()) {
            info.setText(st);
        } else if (!info.getText().toString().startsWith("Found")
                && !info.getText().toString().startsWith("Searching")
                && !info.getText().toString().startsWith("TV not found")
                && !info.getText().toString().startsWith("Tap FIND")) {
            info.setText("1. Open CastLite on the TV.\n2. Tap FIND TV here.\n3. Tap START CASTING."
                    + (Build.VERSION.SDK_INT < 29 ? "\n\n(Audio needs Android 10 or newer.)" : ""));
        }
    }
}
