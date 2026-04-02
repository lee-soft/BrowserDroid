package com.example.browserdroid;

import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sits between the MediaCodec encoder and the WebSocket server.
 * The encoder calls onFrame() on its own thread.
 * Each connected WebSocket client is registered here and gets every frame
 * pushed to it.
 *
 * Frame wire format (very simple):
 * byte[0] = frame type: 0=SPS/PPS config, 1=video frame
 * byte[1…] = raw H264 NAL unit data
 *
 * The browser checks byte[0] to know whether to feed data to
 * VideoDecoder.configure()
 * or VideoDecoder.decode().
 */
public class FrameBroadcaster {

    private static final String TAG = "FrameBroadcaster";
    private Runnable onClientConnected;

    public interface Client {
        void sendFrame(byte[] data);

        boolean isOpen();
    }

    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();

    // The most recent SPS/PPS config packet — sent immediately to any new client
    // so they can start decoding without waiting for the next keyframe
    private byte[] cachedConfig = null;

    public void setOnClientConnected(Runnable r) {
        this.onClientConnected = r;
    }

    public void addClient(Client client) {
        if (onClientConnected != null)
            onClientConnected.run();

        clients.add(client);
        Log.i(TAG, "Client added, total=" + clients.size());

        // Send cached config immediately so the new client can configure its decoder
        if (cachedConfig != null) {
            client.sendFrame(cachedConfig);
        }
    }

    public void removeClient(Client client) {
        clients.remove(client);
        Log.i(TAG, "Client removed, total=" + clients.size());
    }

    public int getClientCount() {
        return clients.size();
    }

    /**
     * Called by the encoder thread for every output buffer.
     *
     * @param data     the NAL unit bytes
     * @param isConfig true if this is SPS/PPS (codec config), false if it's a video
     *                 frame
     */
    public void onFrame(ByteBuffer data, boolean isConfig) {
        // Build the wire packet: 1-byte type prefix + NAL data
        int nalSize = data.remaining();
        byte[] packet = new byte[1 + nalSize];
        packet[0] = isConfig ? (byte) 0 : (byte) 1;
        data.get(packet, 1, nalSize);

        if (isConfig) {
            // Cache config so new clients get it on connect
            cachedConfig = packet;
            Log.d(TAG, "Config packet cached, size=" + nalSize);
        }

        // Broadcast to all connected clients, removing any that have disconnected
        for (Client client : clients) {
            if (client.isOpen()) {
                try {
                    client.sendFrame(packet);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to send to client, removing: " + e.getMessage());
                    clients.remove(client);
                }
            } else {
                clients.remove(client);
            }
        }
    }

    public void reset() {
        cachedConfig = null;
    }
}