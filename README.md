# BrowserDroid — Android Remote Control via Browser

Stream your Android screen to any browser on your local network and control it
with your mouse and keyboard — no apps, no installs, no cables required.

## What it does

- Streams your Android screen live to a browser over **WebSocket + H264/WebCodecs**
- Runs a lightweight **HTTPS + WebSocket server** directly on the phone (port 8443)
- Accepts **touch, swipe, scroll and key input** from the browser via an Accessibility Service
- Runs as a **foreground service** — keeps streaming when you switch apps
- Works over WiFi or with the phone acting as a **hotspot**
- Uses **half-resolution encoding** by default (e.g. 1080p → 540p) for smooth local streaming

---

## How to build

### Requirements

- Android Studio Hedgehog (2023.1) or newer
- Android SDK 34 installed
- A physical Android device (emulators block `MediaProjection` by default)
- USB debugging enabled on the device

### Steps

1. Open Android Studio → **Open** → select the `BrowserDroid` folder
2. Wait for Gradle sync to complete
3. Plug in your phone via USB
4. Press **Run ▶** (or `Shift+F10`)
5. Select your device in the deployment dialog

---

## First run

1. Tap **Start Streaming**
2. The system shows: _"BrowserDroid will start capturing everything displayed on your screen"_
   → Tap **Start now**
3. Tap **Enable Touch Control** and enable `BrowserDroid` in Android Accessibility Settings
4. Open a browser on a device connected to the same WiFi (or the phone's hotspot)
5. Navigate to the URL shown in the app, e.g. `https://192.168.1.x:8443`
6. Accept the self-signed certificate warning in your browser
7. Your phone screen appears — click/scroll to control it

---

## Project structure

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── index.html                          # Browser frontend (WebCodecs player + input)
│   └── BrowserDroid.keystore               # Self-signed TLS cert for HTTPS
└── java/com/example/browserdroid/
    ├── MainActivity.java                   # Permission flow + start/stop UI
    ├── StreamingService.java               # Foreground service — wires everything together
    ├── ScreenEncoder.java                  # MediaProjection → MediaCodec H264 encoder
    ├── FrameBroadcaster.java               # Distributes encoded frames to WebSocket clients
    ├── StreamingServer.java                # NanoHTTPD HTTPS + WebSocket server
    ├── InputController.java                # Translates browser input JSON → gestures
    ├── BrowserDroidAccessibilityService.java  # Dispatches gestures via Accessibility API
    └── NetworkUtil.java                    # Finds the best local IPv4 address
```

### Key classes

**`StreamingService.java`**
The foreground service that owns all components. Starts the encoder, HTTP server
and broadcaster, and wires them together. Stops everything cleanly on destroy.

**`ScreenEncoder.java`**
Creates a `VirtualDisplay` mirroring the real screen, feeds it into a `MediaCodec`
H264 encoder, converts Annex B output to AVCC format, and pushes NAL units to
`FrameBroadcaster`. Runs its drain loop on a dedicated background thread.

**`FrameBroadcaster.java`**
Holds a list of connected WebSocket clients. Caches the latest SPS/PPS config
packet so new clients can start decoding immediately without waiting for the next
keyframe. Broadcasts every frame to all connected clients.

**`StreamingServer.java`**
A `NanoWSD` (NanoHTTPD WebSocket) server listening on port 8443 over TLS.
Serves `index.html` on HTTP GET, and handles two WebSocket endpoints:

- `/stream` — pushes encoded video frames to the browser
- `/control` — receives JSON input events from the browser

**`InputController.java`**
Parses JSON control messages (`touch`, `scroll`, `key`) from the browser
and dispatches them as gestures through `BrowserDroidAccessibilityService`.
Normalised 0–1 coordinates are converted to real pixel positions.

**`BrowserDroidAccessibilityService.java`**
An Android `AccessibilityService` that can dispatch `tap()`, `swipe()` and
global actions (`back`, `home`, `recents`) using `dispatchGesture()`.

---

## Browser controls

| Action       | Control                 |
| ------------ | ----------------------- |
| Tap          | Left click              |
| Swipe / drag | Click and drag          |
| Scroll       | Mouse wheel             |
| Back         | `Escape` or `Backspace` |
| Home         | `F1`                    |
| Recents      | `F2`                    |

---

## Troubleshooting

| Problem                           | Likely cause                      | Fix                                                                                  |
| --------------------------------- | --------------------------------- | ------------------------------------------------------------------------------------ |
| Browser shows certificate warning | Self-signed TLS cert              | Click "Advanced" → "Proceed" in your browser                                         |
| Black / frozen stream             | Some OEMs block VirtualDisplay    | Try "Disable HW overlays" in Developer Options                                       |
| Touch does nothing                | Accessibility service not enabled | Tap "Enable Touch Control" and enable in Settings                                    |
| Can't reach the URL               | Wrong network                     | Ensure phone and browser device are on the same WiFi or hotspot                      |
| Stream is laggy                   | Bandwidth or encoding load        | Increase the `divisor` in `StreamingService.getScreenMetrics()` for lower resolution |
| Service stops immediately         | MediaProjection token expired     | The token is one-shot — tap Start Streaming again                                    |

---

## Configuration

A few constants you may want to tweak:

| File                    | Constant           | Default     | Effect                                 |
| ----------------------- | ------------------ | ----------- | -------------------------------------- |
| `StreamingService.java` | `divisor`          | `2`         | Stream resolution (1 = full, 2 = half) |
| `ScreenEncoder.java`    | `BIT_RATE`         | `2_000_000` | Encoding bitrate in bps                |
| `ScreenEncoder.java`    | `FRAME_RATE`       | `30`        | Target frame rate                      |
| `ScreenEncoder.java`    | `I_FRAME_INTERVAL` | `2`         | Keyframe interval in seconds           |
| `StreamingServer.java`  | `PORT`             | `8443`      | HTTPS / WebSocket port                 |
