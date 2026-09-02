# PECI Wearables — Android research snapshot

This repository contains the smartphone hub application and its Wear OS companion.
## Repository contents

```text
.
├── app/                 # Android smartphone hub application
│   └── src/
│       ├── main/        # Kotlin/Compose app, integrations, protocols and runtime assets
│       ├── test/        # JVM unit tests
│       └── androidTest/ # Android instrumentation tests
├── wear/                # Wear OS companion application
├── models/              # Project-specific TFLite models used by the Android build
├── gradle/              # Gradle version catalog and wrapper
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
```

The Android hub implements the wearable integration layer used in the research prototype, including BLE and Wi-Fi/UDP communication, sensor acquisition, camera/audio pipelines, local and cloud inference interfaces, safety logic, telemetry, and Wear OS communication.

## Requirements

- Android Studio with Android SDK 36 installed
- JDK 17 or newer
- Android device with API level 34+ for the phone app
- Wear OS device with API level 34+ for the companion module, if used

The phone build targets `arm64-v8a`. The Wear OS module targets `armeabi-v7a` as configured in the source snapshot.

## Local configuration

### Navisens developer key

The development repository contained a Navisens developer key in a tracked Android string resource. The public snapshot removes that credential. If Navisens functionality is required, provide the key locally using **one** of the following mechanisms:

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

If no key is supplied, the generated `navisens_developer_key` string is empty.

### Cloud/backend address

The original code contained a lab-network default server address. In this public snapshot it is replaced by the reserved documentation address `192.0.2.1`. Configure the actual backend endpoint from the application settings before using cloud-assisted features.

### Wearable Wi-Fi credentials

The original settings screen included development Wi-Fi credentials as initial field values. These fields are blank in the public snapshot and must be entered at runtime when configuring the glasses.

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

Installation on connected devices can be performed with:

```bash
./gradlew :app:installDebug
./gradlew :wear:installDebug
```
