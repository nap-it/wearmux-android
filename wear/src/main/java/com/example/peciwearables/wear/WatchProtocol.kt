package com.example.peciwearables.wear

import java.nio.ByteBuffer
import java.nio.ByteOrder


object WatchProtocol {

    const val PATH_PREFIX = "/peci/"
    const val PATH_START_IMU = "/peci/imu/start"
    const val PATH_STOP_IMU = "/peci/imu/stop"
    const val PATH_SET_SAMPLE_RATE = "/peci/imu/rate"
    const val PATH_BEEP = "/peci/audio/beep"
    const val PATH_VIBRATE = "/peci/haptic/vibrate"
    const val PATH_NOTIFY = "/peci/notify"
    /** Enviado pelo Watch de volta ao telemóvel após processar uma notificação (para medir RTT). */
    const val PATH_NOTIFY_ACK = "/peci/notify/ack"

    object NotifyType {
        const val WARNING = 0
        const val DANGER = 1
        const val SAFE = 2
    }
    const val PATH_TRAJECTORY_EVENT = "/peci/trajectory/event"
    const val PATH_PHONE_STATE = "/peci/phone/state"
    const val PATH_WATCH_ACTION = "/peci/watch/action"
    const val PATH_IMU_STATE = "/peci/imu/state"
    const val PATH_HEARTBEAT = "/peci/heartbeat"
    const val PATH_HEART_RATE = "/peci/hr"
    const val PATH_ERROR = "/peci/error"

    // ----- ChannelClient -----
    const val CHANNEL_IMU = "/peci/imu/stream"

    // ----- DataClient -----
    const val DATA_PATH_CAPABILITIES = "/peci/capabilities"

    // ----- Capability strings (declaradas em wear.xml em ambos os lados) -----
    const val CAPABILITY_PHONE = "peci_phone_app"
    const val CAPABILITY_WATCH = "peci_watch_app"

    // ----- Packet layout para o ChannelClient IMU stream -----
   
    const val IMU_HEADER_MAGIC = 0x57434550 // "PECW" em little-endian (ASCII)
    const val IMU_HEADER_VERSION = 1
    const val IMU_HEADER_SIZE = 16
    const val IMU_SAMPLE_SIZE = 32

    const val SENSOR_MASK_ACCEL = 0x0001
    const val SENSOR_MASK_GYRO = 0x0002
    const val SENSOR_MASK_MAG = 0x0004

    object VibratePattern {
        const val SHORT = 0
        const val DOUBLE = 1
        const val LONG = 2
        const val TRIPLE = 3
        const val HEARTBEAT = 4
        const val ALARM = 5
    }

    object WatchAction {
        const val START_TRAJECTORY = 0
        const val STOP_TRAJECTORY = 1
        const val AUDIO_BEEP = 2
        const val CONNECT_ALL = 3
    }

    fun encodeSample(
        out: ByteBuffer,
        timestampUs: Long,
        axMg: Short, ayMg: Short, azMg: Short,
        gxMdps: Short, gyMdps: Short, gzMdps: Short,
        mxUtX10: Short, myUtX10: Short, mzUtX10: Short,
    ) {
        out.order(ByteOrder.LITTLE_ENDIAN)
        out.putLong(timestampUs)
        out.putShort(axMg); out.putShort(ayMg); out.putShort(azMg)
        out.putShort(gxMdps); out.putShort(gyMdps); out.putShort(gzMdps)
        out.putShort(mxUtX10); out.putShort(myUtX10); out.putShort(mzUtX10)
        out.putShort(0); out.putShort(0); out.putShort(0)
    }

    fun encodeHeader(
        out: ByteBuffer,
        sensorMask: Int,
        sampleRateHz: Int,
    ) {
        out.order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(IMU_HEADER_MAGIC)
        out.putShort(IMU_HEADER_VERSION.toShort())
        out.putShort(sensorMask.toShort())
        out.putShort(sampleRateHz.toShort())
        // 6 bytes reservados
        out.putShort(0); out.putShort(0); out.putShort(0)
    }
}
