package com.castlite;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * TV side: waits for the phone, decodes the H.264 video onto the screen and plays the audio.
 */
public class ReceiverActivity extends Activity implements SurfaceHolder.Callback {
    // Video is shown this much later so it lines up with the audio pre-buffer.
    // If the picture runs ahead of the sound raise it, if it lags behind lower it.
    private static final long VIDEO_DELAY_NS = 120_000_000L;
    private static final int AUDIO_PREBUFFER_BYTES = P.RATE * 4 / 10; // ~100 ms

    private FrameLayout root;
    private SurfaceView sv;
    private TextView msg;

    private volatile boolean run;
    private volatile ServerSocket ss;
    private volatile DatagramSocket ds;
    private volatile Socket cur;
    private volatile MediaCodec dec;
    private volatile AudioTrack track;
    private long written;
    private boolean started;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        sv = new SurfaceView(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        msg = new TextView(this);
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(24);
        msg.setGravity(Gravity.CENTER);
        msg.setText("Waiting for the phone...\n\nTV address: " + P.ips()
                + "\n\nOn the phone: open CastLite, tap FIND TV, then START CASTING.");
        root.addView(msg, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        sv.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        run = true;
        new Thread(this::discovery).start();
        new Thread(this::serve).start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        stop();
    }

    @Override
    protected void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        run = false;
        final Closeable a = ss, b = ds, c = cur;
        new Thread(() -> {
            close(a);
            close(b);
            close(c);
        }).start();
    }

    private static void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    /** Answers "CASTLITE?" broadcasts from the phone so it can find this TV. */
    private void discovery() {
        try {
            DatagramSocket d = new DatagramSocket(null);
            d.setReuseAddress(true);
            d.bind(new InetSocketAddress(P.DISC));
            ds = d;
            byte[] buf = new byte[32];
            byte[] ans = "CASTLITE!".getBytes();
            while (run) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                d.receive(p);
                if ("CASTLITE?".equals(new String(p.getData(), 0, p.getLength()))) {
                    d.send(new DatagramPacket(ans, ans.length, p.getSocketAddress()));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        try {
            ServerSocket s0 = new ServerSocket();
            s0.setReuseAddress(true);
            s0.bind(new InetSocketAddress(P.PORT));
            ss = s0;
            while (run) {
                Socket c = s0.accept();
                cur = c;
                try {
                    play(c);
                } catch (Exception ignored) {
                } finally {
                    cleanup();
                    close(c);
                    cur = null;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void play(Socket s) throws IOException {
        s.setTcpNoDelay(true);
        Surface surf = sv.getHolder().getSurface();
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        while (run) {
            int type = in.readUnsignedByte();
            int len = in.readInt();
            long pts = in.readLong();
            if (len < 0 || len > (8 << 20)) return;
            byte[] p = new byte[len];
            in.readFully(p);
            switch (type) {
                case P.T_SIZE:
                    startDecoder(p, surf);
                    break;
                case P.T_CFG:
                    feed(p, pts, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                    break;
                case P.T_VID:
                    feed(p, pts, 0);
                    break;
                case P.T_AUD:
                    audio(p);
                    break;
                default:
                    break;
            }
        }
    }

    private void startDecoder(byte[] p, Surface surf) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(p);
        final int w = bb.getInt();
        final int h = bb.getInt();
        releaseDecoder();

        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 << 20);
        if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        final MediaCodec d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        d.configure(f, surf, null, 0);
        d.start();
        dec = d;

        // output thread: shows frames as soon as they are decoded
        new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (dec == d) {
                    int o = d.dequeueOutputBuffer(info, 10000);
                    if (o >= 0) {
                        long delay = track != null ? VIDEO_DELAY_NS : 0;
                        d.releaseOutputBuffer(o, System.nanoTime() + delay);
                    }
                }
            } catch (IllegalStateException ignored) {
                // decoder was released
            }
        }).start();

        runOnUiThread(() -> {
            msg.setVisibility(View.GONE);
            int pw = root.getWidth(), ph = root.getHeight();
            if (pw > 0 && ph > 0) {
                float sc = Math.min((float) pw / w, (float) ph / h);
                sv.setLayoutParams(new FrameLayout.LayoutParams(
                        Math.round(w * sc), Math.round(h * sc), Gravity.CENTER));
            }
        });
    }

    private void feed(byte[] p, long pts, int flags) {
        MediaCodec d = dec;
        if (d == null) return;
        try {
            int i = d.dequeueInputBuffer(10000);
            if (i < 0) return; // decoder is behind: drop this frame
            ByteBuffer b = d.getInputBuffer(i);
            b.clear();
            b.put(p);
            d.queueInputBuffer(i, 0, p.length, pts, flags);
        } catch (IllegalStateException ignored) {
        }
    }

    private void audio(byte[] p) {
        if (track == null) {
            written = 0;
            started = false;
            track = new AudioTrack(AudioManager.STREAM_MUSIC, P.RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                    P.RATE, AudioTrack.MODE_STREAM); // ~250 ms buffer
        }
        track.write(p, 0, p.length);
        written += p.length;
        if (!started && written >= AUDIO_PREBUFFER_BYTES) {
            track.play();
            started = true;
        }
    }

    private void releaseDecoder() {
        MediaCodec d = dec;
        dec = null;
        if (d != null) {
            try {
                d.stop();
            } catch (Exception ignored) {
            }
            d.release();
        }
    }

    private void cleanup() {
        releaseDecoder();
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.stop();
            } catch (Exception ignored) {
            }
            t.release();
        }
        runOnUiThread(() -> {
            msg.setVisibility(View.VISIBLE);
            sv.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        });
    }
}
