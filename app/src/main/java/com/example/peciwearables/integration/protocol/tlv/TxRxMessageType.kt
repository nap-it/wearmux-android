package com.example.peciwearables.integration.protocol.tlv

/**
 * Tipos de mensagem TxRx — espelho exato do array TxRxMessageTypes do SDK TypeScript
 * (BaseConnectionManager.ts), onde o ordinal do enum kotlin == índice do array TS.
 *
 * Composição (total = 77):
 *   InformationMessageTypes       (10)  ordinals  0-9
 *   SensorConfigurationMessageTypes (2) ordinals 10-11
 *   SensorDataMessageTypes          (3) ordinals 12-14
 *   VibrationMessageTypes           (2) ordinals 15-16
 *   FileTransferMessageTypes       (13) ordinals 17-29
 *   TfliteMessageTypes             (16) ordinals 30-45
 *   WifiMessageTypes               (10) ordinals 46-55
 *   CameraMessageTypes              (5) ordinals 56-60
 *   MicrophoneMessageTypes          (5) ordinals 61-65
 *   DisplayMessageTypes            (11) ordinals 66-76
 */
enum class TxRxMessageType {
    // ── Information (0–9) ──
    IS_CHARGING,                   //  0 isCharging
    GET_BATTERY_CURRENT,           //  1 getBatteryCurrent
    GET_MTU,                       //  2 getMtu
    GET_ID,                        //  3 getId
    GET_NAME,                      //  4 getName
    SET_NAME,                      //  5 setName
    GET_TYPE,                      //  6 getType
    SET_TYPE,                      //  7 setType
    GET_CURRENT_TIME,              //  8 getCurrentTime
    SET_CURRENT_TIME,              //  9 setCurrentTime

    // ── SensorConfiguration (10–11) ──
    GET_SENSOR_CONFIGURATION,      // 10 getSensorConfiguration
    SET_SENSOR_CONFIGURATION,      // 11 setSensorConfiguration

    // ── SensorData (12–14) ──
    GET_PRESSURE_POSITIONS,        // 12 getPressurePositions
    GET_SENSOR_SCALARS,            // 13 getSensorScalars
    SENSOR_DATA,                   // 14 sensorData

    // ── Vibration (15–16) ──
    GET_VIBRATION_LOCATIONS,       // 15 getVibrationLocations
    TRIGGER_VIBRATION,             // 16 triggerVibration

    // ── FileTransfer (17–29) ──
    GET_FILE_TYPES,                // 17 getFileTypes
    MAX_FILE_LENGTH,               // 18 maxFileLength
    GET_FILE_TYPE,                 // 19 getFileType
    SET_FILE_TYPE,                 // 20 setFileType
    GET_FILE_LENGTH,               // 21 getFileLength
    SET_FILE_LENGTH,               // 22 setFileLength
    GET_FILE_CHECKSUM,             // 23 getFileChecksum
    SET_FILE_CHECKSUM,             // 24 setFileChecksum
    SET_FILE_TRANSFER_COMMAND,     // 25 setFileTransferCommand
    FILE_TRANSFER_STATUS,          // 26 fileTransferStatus
    GET_FILE_BLOCK,                // 27 getFileBlock
    SET_FILE_BLOCK,                // 28 setFileBlock
    FILE_BYTES_TRANSFERRED,        // 29 fileBytesTransferred

    // ── Tflite (30–45) ──
    GET_TFLITE_NAME,               // 30 getTfliteName
    SET_TFLITE_NAME,               // 31 setTfliteName
    GET_TFLITE_TASK,               // 32 getTfliteTask
    SET_TFLITE_TASK,               // 33 setTfliteTask
    GET_TFLITE_SAMPLE_RATE,        // 34 getTfliteSampleRate
    SET_TFLITE_SAMPLE_RATE,        // 35 setTfliteSampleRate
    GET_TFLITE_SENSOR_TYPES,       // 36 getTfliteSensorTypes
    SET_TFLITE_SENSOR_TYPES,       // 37 setTfliteSensorTypes
    TFLITE_IS_READY,               // 38 tfliteIsReady
    GET_TFLITE_CAPTURE_DELAY,      // 39 getTfliteCaptureDelay
    SET_TFLITE_CAPTURE_DELAY,      // 40 setTfliteCaptureDelay
    GET_TFLITE_THRESHOLD,          // 41 getTfliteThreshold
    SET_TFLITE_THRESHOLD,          // 42 setTfliteThreshold
    GET_TFLITE_INFERENCING_ENABLED,// 43 getTfliteInferencingEnabled
    SET_TFLITE_INFERENCING_ENABLED,// 44 setTfliteInferencingEnabled
    TFLITE_INFERENCE,              // 45 tfliteInference

    // ── Wifi (46–55) ──
    IS_WIFI_AVAILABLE,             // 46 isWifiAvailable
    GET_WIFI_SSID,                 // 47 getWifiSSID
    SET_WIFI_SSID,                 // 48 setWifiSSID
    GET_WIFI_PASSWORD,             // 49 getWifiPassword
    SET_WIFI_PASSWORD,             // 50 setWifiPassword
    GET_WIFI_CONNECTION_ENABLED,   // 51 getWifiConnectionEnabled
    SET_WIFI_CONNECTION_ENABLED,   // 52 setWifiConnectionEnabled
    IS_WIFI_CONNECTED,             // 53 isWifiConnected
    IP_ADDRESS,                    // 54 ipAddress
    IS_WIFI_SECURE,                // 55 isWifiSecure

    // ── Camera (56–60) ──
    CAMERA_STATUS,                 // 56 cameraStatus
    CAMERA_COMMAND,                // 57 cameraCommand
    GET_CAMERA_CONFIGURATION,      // 58 getCameraConfiguration
    SET_CAMERA_CONFIGURATION,      // 59 setCameraConfiguration
    CAMERA_DATA,                   // 60 cameraData

    // ── Microphone (61–65) ──
    MICROPHONE_STATUS,             // 61 microphoneStatus
    MICROPHONE_COMMAND,            // 62 microphoneCommand
    GET_MICROPHONE_CONFIGURATION,  // 63 getMicrophoneConfiguration
    SET_MICROPHONE_CONFIGURATION,  // 64 setMicrophoneConfiguration
    MICROPHONE_DATA,               // 65 microphoneData

    // ── Display (66–76) ──
    IS_DISPLAY_AVAILABLE,          // 66 isDisplayAvailable
    DISPLAY_STATUS,                // 67 displayStatus
    DISPLAY_INFORMATION,           // 68 displayInformation
    DISPLAY_COMMAND,               // 69 displayCommand
    GET_DISPLAY_BRIGHTNESS,        // 70 getDisplayBrightness
    SET_DISPLAY_BRIGHTNESS,        // 71 setDisplayBrightness
    DISPLAY_CONTEXT_COMMANDS,      // 72 displayContextCommands
    DISPLAY_READY,                 // 73 displayReady
    GET_SPRITE_SHEET_NAME,         // 74 getSpriteSheetName
    SET_SPRITE_SHEET_NAME,         // 75 setSpriteSheetName
    SPRITE_SHEET_INDEX;            // 76 spriteSheetIndex

    companion object {
        /**
         * Converte ordinal para enum, ou null se inválido.
         * Equivale ao índice do array TxRxMessageTypes no SDK TS.
         */
        fun fromOrdinal(ordinal: Int): TxRxMessageType? = entries.getOrNull(ordinal)
    }
}
