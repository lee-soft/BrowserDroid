package com.leesoft.browserdroid;

import android.util.Log;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class NetworkUtil {
    private static final String TAG = "NetworkUtil";

    /**
     * Returns the best available IPv4 address — works whether the phone is a
     * WiFi client OR acting as a hotspot (where WifiManager returns nothing).
     *
     * Priority: wlan0 → ap0 → any non-loopback IPv4
     */
    public static String getLocalIpAddress() {
        try {
            // In hotspot mode the interface is usually "ap0" or "wlan1" or "swlan0"
            for (String preferred : new String[] { "ap0", "swlan0", "wlan0", "wlan1", "rndis0" }) {
                NetworkInterface ni = NetworkInterface.getByName(preferred);
                if (ni == null || !ni.isUp())
                    continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        Log.d(TAG, "Using interface " + ni.getName() + ": " + addr.getHostAddress());
                        return addr.getHostAddress();
                    }
                }
            }
            // Fallback: any non-loopback IPv4
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback())
                    continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "IP lookup failed: " + e.getMessage());
        }
        return "?.?.?.?";
    }
}