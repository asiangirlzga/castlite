package com.castlite;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Phone side: captures the screen (H.264) and the audio that apps play (PCM)
 * and pushes both to the TV over one TCP connection.
 */
public class SendService extends Service {
    static volatile boolean running;
    static volatile String status = "";

    private static final int MAX_SIDE = 1280;     // longest video side in pixels
    private static final int BITRATE = 4_000_000; // bits per second

    private MediaProjection mp;
    private VirtualDisplay vd;
    private Socket sock;
    private DataOutputStream dos;
    private final Object wl = new Object();
    private volatile boolean reconfig;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        if (in == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        running = true;
        status = "Connecting...";

        final String ip = in.getStringExtra("ip");
        startFg(ip);
        MediaProjectionManager m =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent data = in.getParcelableExtra("data");
        mp = m.getMediaProjection(in.getIntExtra("code", 0), data);
        mp.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, null);

        new Thread(() -> go(ip)).start();
        return START_NOT_STICKY;
    }

    private void go(String ip) {
        boolean connected = false;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress(ip, P.PORT), 4000);
            sock.setTcpNoDelay(true);
            dos = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream(), 1 << 16));
            connected = true;
            status = "Casting to " + ip;
            if (Build.VERSION.SDK_INT >= 29) new Thread(this::audioLoop).start();
            videoLoop();
        } catch (Exception e) {
            if (running) {
                status = connected
                        ? "Disconnected from the TV."
                        : "Could not reach the TV at " + ip + ". Open CastLite on the TV first.";
            }
        } finally {
            stopSelf();
        }
    }

    /** Encodes the screen to H.264; restarts itself with a new size after rotation. */
    private void videoLoop() throws Exception {
        MediaCodec enc = null;
        Surface surf = null;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try {
            while (running) {
                reconfig = false;
                DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
                float s = Math.min(1f, (float) MAX_SIDE / Math.max(dm.widthPixels, dm.heightPixels));
                int w = Math.round(dm.widthPixels * s) & ~15;
                int h = Math.round(dm.heightPixels * s) & ~15;

                MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
                f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                f.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
                f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
                f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L);

                MediaCodec e2 = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                e2.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                Surface s2 = e2.createInputSurface();
                e2.start();

                if (vd == null) {
                    vd = mp.createVirtualDisplay("cast", w, h, dm.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s2, null, null);
                } else {
                    vd.resize(w, h, dm.densityDpi);
                    vd.setSurface(s2);
                }
                if (enc != null) {
                    enc.stop();
                    enc.release();
                    surf.release();
                }
                enc = e2;
                surf = s2;

                send(P.T_SIZE, 0, ByteBuffer.allocate(8).putInt(w).putInt(h).array(), 8);

                while (running && !reconfig) {
                    int i = enc.dequeueOutputBuffer(bi, 100000);
                    if (i < 0) continue;
                    if (bi.size > 0) {
                        ByteBuffer b = enc.getOutputBuffer(i);
                        byte[] d = new byte[bi.size];
                        b.position(bi.offset);
                        b.get(d);
                        boolean cfg = (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        send(cfg ? P.T_CFG : P.T_VID, bi.presentationTimeUs, d, d.length);
                    }
                    enc.releaseOutputBuffer(i, false);
                }
            }
        } finally {
            if (enc != null) {
                try {
                    enc.stop();
                } catch (Exception ignored) {
                }
                enc.release();
            }
            if (surf != null) surf.release();
        }
    }

    /** Captures what apps are playing (Android 10+). Silently skipped if not possible. */
    @TargetApi(29)
    @SuppressLint("MissingPermission")
    private void audioLoop() {
        AudioRecord rec = null;
        try {
            AudioPlaybackCaptureConfiguration c =
                    new AudioPlaybackCaptureConfiguration.Builder(mp)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();
            AudioFormat af = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(P.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(P.RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(af)
                    .setBufferSizeInBytes(Math.max(min, 8192) * 2)
                    .setAudioPlaybackCaptureConfig(c)
                    .build();
            rec.startRecording();
            byte[] buf = new byte[P.RATE * 4 / 50]; // 20 ms of audio
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n > 0) send(P.T_AUD, 0, buf, n);
            }
        } catch (Exception ignored) {
            // no audio permission or not supported: video keeps working
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
            }
        }
    }

    private void send(int type, long pts, byte[] b, int len) throws IOException {
        synchronized (wl) {
            dos.writeByte(type);
            dos.writeInt(len);
            dos.writeLong(pts);
            dos.write(b, 0, len);
            dos.flush();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        reconfig = true; // screen rotated: rebuild encoder with the new size
    }

    private void startFg(String ip) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel("c", "Casting", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "c");
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = b.setContentTitle("Casting screen")
                .setContentText("Sending to " + ip)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (status.startsWith("Casting") || status.startsWith("Connecting")) status = "";
        final Socket s = sock;
        if (s != null) {
            new Thread(() -> {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }).start();
        }
        if (vd != null) vd.release();
        if (mp != null) mp.stop();
        super.onDestroy();
    }
}
