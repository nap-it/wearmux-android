# Headless Bluetooth bridge

The existing WearMux Android application can optionally take exclusive control of a Brilliant Labs Frame running custom BrilliantSole/BrilliantWear firmware for `wearmux-headless`. Open **Dev / Lab → Overview**, enter the headless WebSocket URL and shared token, and start the bridge. The URL must end in `/android-ble` and contain no query parameters. Use the Droidspaces Debian address or an explicitly forwarded Android-host port, for example `ws://172.28.178.197:8765/android-ble`.

The bridge sends an authenticated outbound WebSocket upgrade with `Authorization: Bearer <token>`. The token is required, is never logged, and is kept only in memory by the service. Protocol frames are JSON text up to 64 KiB; characteristic bytes use standard Base64.

Android owns raw GATT and headless owns the BrilliantSole protocol. The bridge scans the custom Frame main service `ea6d0000-a725-4f9b-893d-c3913e33b39f`, enables RX notifications, negotiates ATT MTU (prefer 517, fallback 23), forwards raw RX values, and serializes TX writes with response. It does not parse sensor/display messages.

Starting the bridge explicitly disconnects and gates the existing glasses client so the two clients cannot compete for GATT. Stopping the bridge explicitly releases the gate and asks the ordinary glasses service to reconnect. Socket loss tears down GATT and queued writes without replay.

The Android app requires nearby-device, notification, and network permissions. Remote deployments should use `wss://`; cleartext `ws://` is retained for the local Droidspaces deployment.

The complete wire contract is in [headless-bluetooth-protocol.md](headless-bluetooth-protocol.md).
