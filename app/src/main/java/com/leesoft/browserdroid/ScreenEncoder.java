package com.leesoft.browserdroid;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.util.Log;
import android.view.Surface;
import java.util.Arrays;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Creates a VirtualDisplay mirroring the screen, feeds it into a MediaCodec
 * H264 encoder,
 * and pushes every output NAL unit to the FrameBroadcaster.
 *
 * Runs its encode loop on a dedicated background thread.
 */
public class ScreenEncoder {

    private static final String TAG = "ScreenEncoder";

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC; // H264
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL = 2; // keyframe every 2 seconds
    private static final int BIT_RATE = 2_000_000; // 2 Mbps — good for local WiFi

    private final MediaProjection mediaProjection;
    private final FrameBroadcaster broadcaster;
    private final int width;
    private final int height;
    private final int density;

    private MediaCodec encoder;
    private VirtualDisplay virtualDisplay;
    private Surface encoderSurface;

    private Thread encodeThread;
    private volatile boolean running = false;

    public ScreenEncoder(MediaProjection mediaProjection, FrameBroadcaster broadcaster,
            int width, int height, int density) {
        this.mediaProjection = mediaProjection;
        this.broadcaster = broadcaster;
        this.width = width;
        this.height = height;
        this.density = density;
    }

    public void start() throws IOException {
        Log.i(TAG, "Starting encoder " + width + "x" + height);

        // 1. Configure MediaCodec as an H264 encoder
        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);

        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000); // repeat every 100ms
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        // Baseline profile = maximum browser compatibility
        format.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31);

        encoder = MediaCodec.createEncoderByType(MIME_TYPE);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        // 2. Get the input Surface — VirtualDisplay will render directly into this
        encoderSurface = encoder.createInputSurface();
        encoder.start();

        // 3. Create VirtualDisplay rendering into the encoder surface
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "BrowserDroidDisplay",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface,
                null, null);
        Log.i(TAG, "VirtualDisplay created");

        // 4. Start the drain loop
        running = true;
        encodeThread = new Thread(this::drainEncoder, "EncoderDrain");
        encodeThread.start();
    }

    public void requestKeyframe() {
        if (encoder != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(params);
            Log.i(TAG, "Keyframe requested");
        }
    }

    /**
     * Continuously pulls encoded buffers from MediaCodec and sends them to the
     * broadcaster.
     * Blocks on dequeueOutputBuffer() — wakes up whenever the encoder produces
     * output.
     */
    private void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        Log.i(TAG, "Drain loop started");

        while (running) {
            int outputBufferId = encoder.dequeueOutputBuffer(bufferInfo, 10_000);

            if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER)
                continue;
            if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Log.d(TAG, "Output format changed: " + encoder.getOutputFormat());
                continue;
            }
            if (outputBufferId < 0)
                continue;
            Log.d(TAG, "Got buffer id=" + outputBufferId + " size=" + bufferInfo.size + " flags=" + bufferInfo.flags);

            try {
                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferId);
                if (outputBuffer == null)
                    continue;

                outputBuffer.position(bufferInfo.offset);
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                boolean isEos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                if (bufferInfo.size > 0) {
                    if (isConfig) {
                        // Convert Annex B SPS/PPS → AVCC format for WebCodecs
                        byte[] avcc = annexBToAvcc(outputBuffer);
                        if (avcc != null) {
                            broadcaster.onFrame(java.nio.ByteBuffer.wrap(avcc), true);
                        }
                    } else {
                        // Convert Annex B frame start codes → 4-byte length prefix
                        byte[] avcc = annexBFrameToAvcc(outputBuffer);
                        Log.d(TAG,
                                "Sending frame, avcc size=" + avcc.length + " clients=" + broadcaster.getClientCount());

                        broadcaster.onFrame(java.nio.ByteBuffer.wrap(avcc), false);
                    }
                }

                if (isEos) {
                    Log.i(TAG, "EOS");
                    break;
                }

            } finally {
                encoder.releaseOutputBuffer(outputBufferId, false);
            }
        }
        Log.i(TAG, "Drain loop ended");
    }

    /**
     * Convert Annex B SPS+PPS config packet to AVCC extradata format.
     * Annex B: 00 00 00 01 [SPS] 00 00 00 01 [PPS]
     * AVCC: 01 [profile] [constraints] [level] FF E1 [sps_len] [SPS] 01 [pps_len]
     * [PPS]
     */
    private static byte[] annexBToAvcc(java.nio.ByteBuffer annexB) {
        byte[] data = new byte[annexB.remaining()];
        annexB.get(data);

        // Split on start codes (00 00 00 01 or 00 00 01)
        java.util.List<byte[]> nalUnits = splitNalUnits(data);
        if (nalUnits.size() < 2) {
            Log.w(TAG, "Expected SPS+PPS, got " + nalUnits.size() + " NAL units");
            return null;
        }

        byte[] sps = nalUnits.get(0);
        byte[] pps = nalUnits.get(1);

        // Build AVCC extradata
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            out.write(1); // version
            out.write(sps[1]); // profile
            out.write(sps[2]); // profile compatibility
            out.write(sps[3]); // level
            out.write(0xFF); // 6 reserved bits + lengthSizeMinusOne=3 (4-byte lengths)
            out.write(0xE1); // 3 reserved bits + numSPS=1
            out.write((sps.length >> 8) & 0xFF);
            out.write(sps.length & 0xFF);
            out.write(sps);
            out.write(1); // numPPS
            out.write((pps.length >> 8) & 0xFF);
            out.write(pps.length & 0xFF);
            out.write(pps);
        } catch (java.io.IOException e) {
            /* ByteArrayOutputStream never throws */ }

        return out.toByteArray();
    }

    /**
     * Convert an Annex B video frame to AVCC (replace start codes with 4-byte
     * length prefixes).
     */
    private static byte[] annexBFrameToAvcc(java.nio.ByteBuffer annexB) {
        byte[] data = new byte[annexB.remaining()];
        annexB.get(data);

        java.util.List<byte[]> nalUnits = splitNalUnits(data);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            for (byte[] nal : nalUnits) {
                int len = nal.length;
                out.write((len >> 24) & 0xFF);
                out.write((len >> 16) & 0xFF);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
                out.write(nal);
            }
        } catch (java.io.IOException e) {
            /* never throws */ }
        return out.toByteArray();
    }

    /**
     * Split Annex B bytestream into individual NAL units, stripping start codes.
     */
    private static java.util.List<byte[]> splitNalUnits(byte[] data) {
        java.util.List<byte[]> nals = new java.util.ArrayList<>();
        int start = -1;
        int startLen = 0;

        for (int i = 0; i < data.length - 3; i++) {
            boolean is4byte = (i + 3 < data.length)
                    && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1;
            boolean is3byte = data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1;

            if (is4byte || is3byte) {
                if (start >= 0) {
                    nals.add(java.util.Arrays.copyOfRange(data, start, i));
                }
                startLen = is4byte ? 4 : 3;
                start = i + startLen;
                i += startLen - 1;
            }
        }
        if (start >= 0 && start < data.length) {
            nals.add(java.util.Arrays.copyOfRange(data, start, data.length));
        }
        return nals;
    }

    public void stop() {
        Log.i(TAG, "Stopping encoder");
        running = false;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (encoder != null) {
            try {
                encoder.signalEndOfInputStream();
            } catch (Exception e) {
                // ignore
            }
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing encoder: " + e.getMessage());
            }
            encoder = null;
        }

        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }

        if (encodeThread != null) {
            try {
                encodeThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            encodeThread = null;
        }
    }
}