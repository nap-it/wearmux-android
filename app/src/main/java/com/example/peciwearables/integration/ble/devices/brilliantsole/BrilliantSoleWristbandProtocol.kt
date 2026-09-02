package com.example.peciwearables.integration.ble.devices.brilliantsole

// Códigos de mensagem e constantes do protocolo Brilliant Sole (SDK).
// ── TxRxMessageTypes enum indices (do SDK BaseConnectionManager.ts) ──
// InformationMessageTypes
const val MSG_IS_CHARGING = 0
const val MSG_GET_BATTERY_CURRENT = 1
const val MSG_GET_MTU = 2
const val MSG_GET_ID = 3
const val MSG_GET_NAME = 4
const val MSG_SET_NAME = 5
const val MSG_GET_TYPE = 6
const val MSG_SET_TYPE = 7
const val MSG_GET_CURRENT_TIME = 8
const val MSG_SET_CURRENT_TIME = 9
// SensorConfigurationMessageTypes
const val MSG_GET_SENSOR_CONFIG = 10
const val MSG_SET_SENSOR_CONFIG = 11
// SensorDataMessageTypes
const val MSG_GET_PRESSURE_POSITIONS = 12
const val MSG_GET_SENSOR_SCALARS = 13
const val MSG_SENSOR_DATA = 14
// VibrationMessageTypes
const val MSG_GET_VIBRATION_LOCATIONS = 15
const val MSG_TRIGGER_VIBRATION = 16
// FileTransferMessageTypes (17..29) reservados
const val MSG_GET_FILE_TYPES = 17
const val MSG_MAX_FILE_LENGTH = 18
const val MSG_GET_FILE_TYPE = 19
const val MSG_SET_FILE_TYPE = 20
const val MSG_GET_FILE_LENGTH = 21
const val MSG_SET_FILE_LENGTH = 22
const val MSG_GET_FILE_CHECKSUM = 23
const val MSG_SET_FILE_CHECKSUM = 24
const val MSG_SET_FILE_TRANSFER_COMMAND = 25
const val MSG_FILE_TRANSFER_STATUS = 26
const val MSG_GET_FILE_BLOCK = 27
const val MSG_SET_FILE_BLOCK = 28
const val MSG_FILE_BYTES_TRANSFERRED = 29

// TfliteMessageTypes
const val MSG_GET_TFLITE_NAME = 30
const val MSG_SET_TFLITE_NAME = 31
const val MSG_GET_TFLITE_TASK = 32
const val MSG_SET_TFLITE_TASK = 33
const val MSG_GET_TFLITE_SAMPLE_RATE = 34
const val MSG_SET_TFLITE_SAMPLE_RATE = 35
const val MSG_GET_TFLITE_SENSOR_TYPES = 36
const val MSG_SET_TFLITE_SENSOR_TYPES = 37
const val MSG_TFLITE_IS_READY = 38
const val MSG_GET_TFLITE_CAPTURE_DELAY = 39
const val MSG_SET_TFLITE_CAPTURE_DELAY = 40
const val MSG_GET_TFLITE_THRESHOLD = 41
const val MSG_SET_TFLITE_THRESHOLD = 42
const val MSG_GET_TFLITE_INFERENCING_ENABLED = 43
const val MSG_SET_TFLITE_INFERENCING_ENABLED = 44
const val MSG_TFLITE_INFERENCE = 45

const val TFLITE_TASK_CLASSIFICATION = 0
const val TFLITE_TASK_REGRESSION = 1

const val FILE_TYPE_TFLITE = 0
const val FILE_TRANSFER_CMD_START_SEND = 0
const val FILE_TRANSFER_STATUS_IDLE = 0
const val FILE_TRANSFER_STATUS_SENDING = 1

// ── Vibration type indices ──
const val VIB_TYPE_WAVEFORM_EFFECT = 0
const val VIB_TYPE_WAVEFORM = 1

// ── Vibration waveform effects (from VibrationWaveformEffects.ts) ──
const val EFFECT_NONE = 0
const val EFFECT_STRONG_CLICK_100 = 1
const val EFFECT_STRONG_CLICK_60 = 2
const val EFFECT_DOUBLE_CLICK_100 = 10
const val EFFECT_STRONG_BUZZ_100 = 14
const val EFFECT_ALERT_750MS = 15
const val EFFECT_ALERT_1000MS = 16
const val EFFECT_PULSING_STRONG_100 = 52
const val EFFECT_SMOOTH_HUM_50 = 119

// Sensor types
const val SENSOR_TYPE_PRESSURE = 0
const val SENSOR_TYPE_ACCELERATION = 1
const val SENSOR_TYPE_GRAVITY = 2
const val SENSOR_TYPE_LINEAR_ACCELERATION = 3
const val SENSOR_TYPE_GYROSCOPE = 4
const val SENSOR_TYPE_MAGNETOMETER = 5
