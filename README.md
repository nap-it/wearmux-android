# WearMux — Android hub

This repository contains the smartphone hub application and its Wear OS companion. The hub connects heterogeneous wearables through capability-oriented adapters, processes camera, audio, inertial and context data on the phone, and either consumes the results locally or offloads them to a server.

It is the Android host described in the WearMux paper. The headless Node.js host and the server-side services live in separate repositories.

### Citation

If you find this code useful in your research, please consider citing:

```bibtex
@INPROCEEDINGS{Tavares2026,
    author={Guilherme Tavares and Rafael Soares and André Clérigo and Gonçalo Silva and Gabriel Silva and Tomás Cruz and João Abrunhosa and Pedro Laredo and Pedro Rito and Susana Sargento},
    booktitle={2026 IEEE 29th International Symposium on Personal, Indoor and Mobile Radio Communications (WPMC)},
    title={WearMux: Real-Time Multimodal Sensing and Feedback across Heterogeneous Wearables}
}
```

## Repository contents

```text
.
├── app/                    # Android smartphone hub application
│   └── src/
│       ├── main/           # Kotlin/Compose app, integration layer, native whisper.cpp
│       ├── test/           # JVM unit tests
│       └── androidTest/    # Android instrumentation tests
├── wear/                   # Wear OS companion application
├── models/                 # TFLite models copied into the app assets at build time
├── scripts/                # Helper scripts (YAMNet download, watch test)
├── gradle/                 # Gradle version catalog and wrapper
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

The integration layer under `app/src/main/java/com/example/peciwearables/integration/` follows the architecture figure in the paper, one package per block:

```text
integration/
├── hub/          Device hub: discovery, adapter selection, session ownership
├── adapters/     Adapters and sessions, including the per-device BLE clients
│   └── devices/  omi, brilliantsole, esp32, galaxywatch
├── modules/      Modality processing
│   ├── camera/       JPEG assembly, stream metrics, phone and glasses frames
│   ├── microphone/   PCM pipeline, Opus decoding, WAV capture, KWS/STT sessions
│   ├── sensors/      IMU fusion, sample-rate policy
│   ├── context/      GPS, pedestrian dead reckoning, route recording
│   ├── android/      Phone camera, microphone, sensors, audio output
│   └── wearos/       Watch client, protocol and action routing
├── observation/  Timestamper: the hub timestamp carried by every observation
├── api/          HTTP and WebSocket clients for the remote services
├── output/       Output dispatcher: alerts to the watch, wristband and phone
├── consumer/     Local consumer: telemetry reporting
├── safety/       Use-case logic and decision mapping
├── protocol/     Wire formats (packet headers, TLV, IMU payloads)
├── udp/          UDP session handling for the Wi-Fi image path
└── network/      NSD registration, Wi-Fi locks, interface helpers
```

## Supported devices

| Device | Transport | Capabilities exposed to the hub |
| --- | --- | --- |
| Omi AI glasses (ESP-based) | BLE GATT, Wi-Fi/UDP | Camera, microphone, 9-DoF IMU, Wi-Fi handoff |
| Brilliant Wear wristband/insole | BLE GATT | 9-DoF IMU, haptics, on-device ML |
| Wear OS watch | Wear OS Data Layer | IMU, heart rate, audiovisual alerts, vibration |
| ESP32-S3 camera board | Wi-Fi/UDP | Camera, IMU |
| The phone itself | Local Android APIs | Camera, microphone, IMU, GPS, audio alerts |

Adding a device means writing an adapter and a session under `integration/adapters/devices/`. Nothing above the adapter layer refers to a device by brand; the UI and the pipelines reason about capabilities.

## Requirements

- Android Studio with Android SDK 36 installed
- JDK 17 or newer (the build has been exercised with JDK 17; Gradle 9.1 ships with the wrapper)
- Phone running Android 14 (API 34) or newer, `arm64-v8a`
- Wear OS device running API 34 or newer for the companion module, `armeabi-v7a`
- A machine running the WearMux server repository if you intend to use offloaded inference

Bluetooth Low Energy is required. The camera is optional at the manifest level, so the app installs on devices without one.

## Local configuration

### SDK location

Gradle reads the SDK path from `local.properties`, which is not tracked. Android Studio writes it on first open; to create it by hand:

```properties
sdk.dir=/path/to/Android/Sdk
```

### Navisens developer key

The trajectory view is backed by Navisens and needs a developer key. The key is not stored in the repository; it is injected into the `navisens_developer_key` string resource at build time. Supply it through **one** of the following:

1. Add this line to the untracked `local.properties` file:

```properties
NAVISENS_DEVELOPER_KEY=your_key_here
```

2. Export an environment variable before building:

```bash
export NAVISENS_DEVELOPER_KEY=your_key_here
```

3. Pass a Gradle property:

```bash
./gradlew :app:assembleDebug -PNAVISENS_DEVELOPER_KEY=your_key_here
```

If no key is supplied the generated string is empty and the trajectory view stays inactive; every other feature builds and runs normally.

### Server address

The compiled default is `http://172.20.10.2:8080`, defined in `integration/CloudConfig.kt`, with the keyword spotter on port 9091 and the streaming transcriber on 9090. You do not need to rebuild to change it: open **Settings** in the app and set the unified server URL. The value is stored in shared preferences and reused on the next start.

### Wearable Wi-Fi credentials

The glasses join the same network as the phone. The dialog under **Devices** asks only for the password; nothing is stored in the repository.

### TFLite models

`app/build.gradle.kts` copies the models from `models/` into `app/src/main/assets` before every build. The phone classifier becomes `peci_model.tflite` and the wristband model becomes `trained.tflite`. YAMNet, used for ambient sound classification, is optional and can be fetched with `scripts/download_yamnet.sh`; without it that path falls back to an RMS heuristic.

### Native transcription

If `app/src/main/cpp/whisper.cpp/src/whisper.cpp` is present the build compiles the native library and on-device transcription becomes available. If the directory is absent the build skips CMake entirely and the app still compiles.

## Build

From the repository root:

```bash
# Build the Android phone app
./gradlew :app:assembleDebug

# Run JVM unit tests
./gradlew :app:testDebugUnitTest

# Build the Wear OS companion
./gradlew :wear:assembleDebug
```

Installation on connected devices:

```bash
./gradlew :app:installDebug
./gradlew :wear:installDebug
```

When both a phone and a watch are attached, pass the serial to disambiguate:

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Running the application

### First start

Grant the permissions the app requests on launch: camera, microphone, location, nearby devices and notifications. Location is required by Android for BLE scanning even when you do not use GPS, and the foreground service will not start without notification permission.

The app runs its work inside a foreground service, so acquisition survives the screen turning off. A persistent notification indicates that the service is active.

### Screens

- **Devices** — scan, connect and disconnect wearables. Shows connection state, battery, firmware and signal strength per device, and holds the dialogs for Wi-Fi handoff and camera/microphone profiles.
- **Status** — live view of what the hub is receiving: camera preview, IMU streams, GPS and dead-reckoning position, watch state, safety decisions and the event log.
- **Dev** — hidden unless developer mode is enabled in Settings. Raw logs, latency benchmarks, UDP diagnostics and per-use-case toggles.
- **Settings** — server URL, processing location, alert preferences and developer mode.

### Connecting a wearable

1. Open **Devices** and start a scan.
2. Pick a candidate from the list, or use automatic connection to take the first compatible device.
3. The hub reads the advertising data to choose a likely adapter, connects, and only confirms the match after reading the services the device actually exposes. A confirmed connection produces a session that advertises its capabilities.
4. For the glasses over Wi-Fi, connect over BLE first, then use the Wi-Fi dialog. The device joins the phone network and reports its address back over BLE; the image stream then moves to UDP.

The Wear OS companion needs no scanning. Install it, and the phone and watch find each other through the Data Layer.

### Choosing where inference runs

Activity classification can run in three places, selected in Settings:

- **Phone** — TFLite model on the handset
- **Wristband** — model uploaded to the wearable, inference on-device, result delivered over BLE
- **Server** — features posted to the configured endpoint

Object detection and depth estimation are selected per mode in the camera view: local ONNX inference on the phone, or the remote services when the server URL is set.

Changing the processing location also changes the sensor sample rate the hub asks the wearable for. On-device inference needs 10 ms; when the camera or microphone is competing for the radio the rate drops to 200 ms; otherwise it is 50 ms.

### Recording routes

Under **Status**, start a route recording to capture a trajectory from GPS and dead reckoning. Routes are saved on the device, can be reactivated later, and are used by the crossing-zone logic.

### Benchmarks

Latency runs write CSV files to the app external files directory:

```bash
adb shell ls /sdcard/Android/data/com.example.peciwearables/files/benchmarks
adb pull /sdcard/Android/data/com.example.peciwearables/files/benchmarks .
```

One file per path: `yolo_latency.csv`, `depth_latency.csv`, `kws_latency.csv`, `decisions_latency.csv` and the camera and microphone transport measurements. Every row carries the hub timestamp, so rows from different sources can be aligned.

## Driving the app from the command line

The service accepts broadcasts, which is the practical way to script a measurement session without touching the screen. The receiver forwards them to the service unchanged.

```bash
# Start and stop the foreground service
adb shell am broadcast -a com.example.peciwearables.START_SERVICE
adb shell am broadcast -a com.example.peciwearables.STOP_SERVICE

# Connect devices
adb shell am broadcast -a com.example.peciwearables.CONNECT_GLASSES
adb shell am broadcast -a com.example.peciwearables.CONNECT_WRISTBAND
adb shell am broadcast -a com.example.peciwearables.CONNECT_AUTO

# Camera and microphone
adb shell am broadcast -a com.example.peciwearables.TAKE_PICTURE
adb shell am broadcast -a com.example.peciwearables.START_STREAM
adb shell am broadcast -a com.example.peciwearables.STOP_STREAM
adb shell am broadcast -a com.example.peciwearables.START_MICROPHONE

# Move the glasses to Wi-Fi, then open the UDP session on the address they report
adb shell am broadcast -a com.example.peciwearables.SEND_WIFI --es ssid MyNetwork --es password secret
adb shell am broadcast -a com.example.peciwearables.CONNECT_UDP

# Select where activity inference runs: APP, WRISTBAND or SERVER
adb shell am broadcast -a com.example.peciwearables.SET_ML_PROCESSING_LOCATION \
  --es ml_processing_location WRISTBAND

# Point the hub at a server without opening Settings
adb shell am broadcast -a com.example.peciwearables.UNIFIED_SERVER_SET_URL \
  --es unified_server_url http://192.168.1.50:8080
```

`CONNECT_UDP` takes no address: the glasses report theirs over BLE after the Wi-Fi handoff, and the hub uses that. The full list of actions and extras is in `integration/WearableServiceActions.kt`.

## Watching the logs

```bash
adb logcat -s WearableService:D SafetyLog:D CloudInference:D SherpaKwsClient:D
```

Lines prefixed with `BENCH|` carry the latency measurements that also reach the CSV files.

## Talking to the server

The hub expects the WearMux server on the address configured in Settings:

| Purpose | Endpoint |
| --- | --- |
| Object detection | `POST {base}/detect` |
| Depth estimation | `POST {base}/depth` |
| Scene description | `POST {base}/inputs/visual_assistant` |
| Keyword and transcription events | `POST {base}/inputs/audio_stt` |
| Head pose | `POST {base}/inputs/glasses_pose` |
| Telemetry and returned decisions | `POST {base}/telemetry` |
| Keyword spotting stream | `ws://{host}:9091` |
| Streaming transcription | `ws://{host}:9090` |

Every request carries `hub_timestamp`, stamped when the observation left acquisition rather than when the request was built. The server never replaces it; that value is what lets the fusion engine correlate motion, vision and speech coming from different devices.

## Wear OS companion

The `wear` module streams wrist IMU to the phone and renders alerts. It holds the alert activity, vibration patterns, beeps, the notifier, the phone command listener and the state mirror that keeps the watch face in sync with the hub. Commands and acknowledgements travel over the Data Layer using the shared protocol in `WatchProtocol.kt`.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

The JVM suite covers the protocol parsers, the camera and audio pipelines, the adapter registry and session lifecycle, the safety evaluators, the sample-rate policy and the hub timestamp contract. Instrumentation tests under `app/src/androidTest` need a connected device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Troubleshooting

**Scanning finds nothing.** Location permission must be granted and location services switched on; Android blocks BLE results otherwise. Check that the wearable is not already connected to another phone.

**The glasses connect but no images arrive.** Confirm the camera profile under Devices. Over BLE the throughput limits the frame rate; if you need a higher rate, move the device to Wi-Fi and let the UDP session take over.

**The Wi-Fi session drops and does not come back.** The hub runs a watchdog that reconnects and falls back to BLE. Watch `WearableService` logs for `UDP DBG` lines. Keeping the phone screen on during long captures avoids Wi-Fi power-saving behaviour on some handsets.

**Cloud features do nothing.** Check the server URL in Settings, and that the phone and server are on the same network. `GET {base}/health` from the phone browser is the quickest confirmation.

**The watch does not appear.** Both apps must be installed and the watch paired to the same phone. The companion is a separate APK; installing the phone app alone is not enough.

**Build fails on the native library.** Remove or ignore the whisper.cpp directory to build without on-device transcription; the Gradle script detects its absence and skips CMake.
