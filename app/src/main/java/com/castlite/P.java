package com.castlite;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Collections;

/** Shared constants and small network helpers. */
final class P {
    static final int PORT = 5000;   // TV listens here (TCP)
    static final int DISC = 5001;   // TV answers discovery here (UDP)
    static final int RATE = 44100;  // audio sample rate, 16-bit stereo PCM

    // packet types: [type:1][length:4][pts:8][payload]
    static final int T_SIZE = 0;    // payload: width:int, height:int
    static final int T_CFG = 1;     // H.264 SPS/PPS
    static final int T_VID = 2;     // H.264 frame
    static final int T_AUD = 3;     // raw PCM

    private P() {
    }

    /** IPv4 addresses of this device (without mobile-data interfaces). */
    static String ips() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = n.getName();
                if (!n.isUp() || n.isLoopback() || name.startsWith("rmnet")
                        || name.startsWith("ccmni") || name.startsWith("tun")
                        || name.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address) {
                        if (sb.length() > 0) sb.append("  ");
                        sb.append(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() == 0 ? "(not connected)" : sb.toString();
    }

    /** Broadcasts a hello on every local network and returns the first TV that answers, or null. */
    static String discover() {
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            ds.setBroadcast(true);
            ds.setSoTimeout(300);
            byte[] q = "CASTLITE?".getBytes();
            for (int round = 0; round < 8; round++) {
                try {
                    ds.send(new DatagramPacket(q, q.length,
                            InetAddress.getByName("255.255.255.255"), DISC));
                } catch (Exception ignored) {
                }
                for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    for (InterfaceAddress a : n.getInterfaceAddresses()) {
                        InetAddress b = a.getBroadcast();
                        if (b == null) continue;
                        try {
                            ds.send(new DatagramPacket(q, q.length, b, DISC));
                        } catch (Exception ignored) {
                        }
                    }
                }
                try {
                    DatagramPacket r = new DatagramPacket(new byte[32], 32);
                    ds.receive(r);
                    if ("CASTLITE!".equals(new String(r.getData(), 0, r.getLength()))) {
                        return r.getAddress().getHostAddress();
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (ds != null) ds.close();
        }
        return null;
    }
}
