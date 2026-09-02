package com.example.peciwearables.integration.ble.devices.omi

// Constantes de protocolo, comandos e timings do OmiGlassesBleClient.

internal const val TARGET_MTU = 517
internal const val MAX_AUTO_CONNECT_RETRIES = 3
internal const val AUTO_RETRY_BASE_DELAY_MS = 800L
internal const val CONNECT_ATTEMPT_TIMEOUT_MS = 10_000L
internal const val SDK_DEVICE_TYPE_GLASSES = 4

internal const val CAMERA_COMMAND_FOCUS = 0
internal const val CAMERA_COMMAND_TAKE_PICTURE = 1
internal const val CAMERA_COMMAND_STOP = 2
internal const val CAMERA_COMMAND_SLEEP = 3
internal const val CAMERA_COMMAND_WAKE = 4

internal const val MICROPHONE_COMMAND_START = 0
internal const val MICROPHONE_COMMAND_STOP = 1

internal const val SENSOR_TYPE_CAMERA = 15
internal const val SENSOR_TYPE_MICROPHONE = 16
internal const val SENSOR_TYPE_ACCELERATION = 1
internal const val SENSOR_TYPE_GYROSCOPE = 4
internal const val SENSOR_TYPE_MAGNETOMETER = 5
internal const val SENSOR_TYPE_GAME_ROTATION = 6

// Throttle do IMU só-orientação dos óculos.
internal const val IMU_FORWARD_MIN_INTERVAL_MS = 20L
internal const val DEFAULT_IMU_RATE_MS = 50
internal const val WIFI_VIDEO_IMU_RATE_MS = 100
internal const val CAMERA_CONFIGURATION_RESOLUTION = 0
internal const val CAMERA_CONFIGURATION_QUALITY_FACTOR = 1
internal const val DEFAULT_CAMERA_RATE_MS = 20
internal const val DEFAULT_MICROPHONE_RATE_MS = 5
internal const val DEFAULT_MICROPHONE_SAMPLE_RATE = 16_000
internal const val DEFAULT_MICROPHONE_BIT_DEPTH = 16
internal const val FILE_TYPE_CAMERA_IMAGE = 4
internal const val SDK_STREAM_CAPTURE_TIMEOUT_MS = 800L
internal const val SDK_STREAM_MAX_FPS = 10
internal const val SDK_STREAM_PENDING_WRITE_RETRY_MS = 40L
internal const val SDK_STREAM_PENDING_LOG_COOLDOWN_MS = 2_000L
internal const val SDK_STREAM_STALLED_WRITE_WARN_MS = 1_200L

// Ao fim deste nº de timeouts seguidos, faz ping STOP→WAKE para destravar o firmware.
internal const val SDK_STREAM_STUCK_TIMEOUTS_THRESHOLD = 12
internal const val SDK_STREAM_STUCK_COOLDOWN_MS = 6_000L
internal const val GATT_OP_TIMEOUT_MS = 1_800L
