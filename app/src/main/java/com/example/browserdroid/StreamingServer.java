package com.example.browserdroid;

import android.content.Context;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class StreamingServer extends NanoWSD {

    private static final String TAG = "StreamingServer";
    public static final int PORT = 8443;

    private final FrameBroadcaster broadcaster;
    private final ControlHandler controlHandler;
    private final Context context;

    public interface ControlHandler {
        void onControlMessage(String json);
    }

    public StreamingServer(Context context, FrameBroadcaster broadcaster, ControlHandler controlHandler) {
        super("0.0.0.0", PORT);
        this.context = context;
        this.broadcaster = broadcaster;
        this.controlHandler = controlHandler;
    }

    public void makeSecure(Context context) throws IOException {
        try {
            java.io.InputStream ks = context.getAssets().open("BrowserDroid.keystore");

            // Load the KeyStore from the InputStream manually
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("PKCS12");
            keyStore.load(ks, "BrowserDroid123".toCharArray());
            ks.close();

            // Build a KeyManagerFactory from it
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory
                    .getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, "BrowserDroid123".toCharArray());

            // Use the NanoHTTPD overload that accepts KeyManagerFactory
            makeSecure(NanoHTTPD.makeSSLSocketFactory(keyStore, kmf), null);

        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to load keystore: " + e.getMessage(), e);
        }
    }

    @Override
    protected NanoWSD.WebSocket openWebSocket(NanoHTTPD.IHTTPSession session) {
        String uri = session.getUri();
        Log.i(TAG, "WebSocket upgrade: " + uri);
        if ("/stream".equals(uri))
            return new StreamWebSocket(session, broadcaster);
        if ("/control".equals(uri))
            return new ControlWebSocket(session, controlHandler);
        return new NanoWSD.WebSocket(session) {
            @Override
            protected void onOpen() {
                try {
                    close(NanoWSD.WebSocketFrame.CloseCode.UnsupportedData, "Unknown path", false);
                } catch (IOException e) {
                }
            }

            @Override
            protected void onClose(NanoWSD.WebSocketFrame.CloseCode c, String r, boolean i) {
            }

            @Override
            protected void onMessage(NanoWSD.WebSocketFrame f) {
            }

            @Override
            protected void onPong(NanoWSD.WebSocketFrame f) {
            }

            @Override
            protected void onException(IOException e) {
            }
        };
    }

    @Override
    public NanoHTTPD.Response serveHttp(NanoHTTPD.IHTTPSession session) {
        String uri = session.getUri();
        Log.i(TAG, "HTTP " + session.getMethod() + " " + uri);
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            try {
                InputStream is = context.getAssets().open("index.html");
                byte[] bytes = is.readAllBytes();
                String html = new String(bytes, StandardCharsets.UTF_8);
                is.close();
                return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", html);
            } catch (IOException e) {
                Log.e(TAG, "Could not read index.html from assets", e);
                return newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain",
                        "Could not load frontend");
            }
        }
        return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found");
    }

    // ── Stream WebSocket ──────────────────────────────────────────────────────

    private static class StreamWebSocket extends NanoWSD.WebSocket implements FrameBroadcaster.Client {
        private final FrameBroadcaster broadcaster;
        private volatile boolean open = false;

        StreamWebSocket(NanoHTTPD.IHTTPSession session, FrameBroadcaster broadcaster) {
            super(session);
            this.broadcaster = broadcaster;
        }

        @Override
        protected void onOpen() {
            open = true;
            Log.i(TAG, "Stream client connected");
            broadcaster.addClient(this);
        }

        @Override
        protected void onClose(NanoWSD.WebSocketFrame.CloseCode c, String r, boolean i) {
            open = false;
            broadcaster.removeClient(this);
        }

        @Override
        protected void onMessage(NanoWSD.WebSocketFrame f) {
        }

        @Override
        protected void onPong(NanoWSD.WebSocketFrame f) {
        }

        @Override
        protected void onException(IOException e) {
            open = false;
            broadcaster.removeClient(this);
        }

        @Override
        public void sendFrame(byte[] data) {
            try {
                send(data);
            } catch (IOException e) {
                Log.w(TAG, "Send failed: " + e.getMessage());
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    // ── Control WebSocket ─────────────────────────────────────────────────────

    private static class ControlWebSocket extends NanoWSD.WebSocket {
        private final ControlHandler handler;

        ControlWebSocket(NanoHTTPD.IHTTPSession session, ControlHandler handler) {
            super(session);
            this.handler = handler;
        }

        @Override
        protected void onOpen() {
            Log.i(TAG, "Control client connected");
        }

        @Override
        protected void onClose(NanoWSD.WebSocketFrame.CloseCode c, String r, boolean i) {
        }

        @Override
        protected void onPong(NanoWSD.WebSocketFrame f) {
        }

        @Override
        protected void onException(IOException e) {
        }

        @Override
        protected void onMessage(NanoWSD.WebSocketFrame frame) {
            String msg = frame.getTextPayload();
            if (msg != null && !msg.isEmpty())
                handler.onControlMessage(msg);
        }
    }

}