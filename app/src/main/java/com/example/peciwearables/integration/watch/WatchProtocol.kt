package com.example.peciwearables.integration.watch

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
    /** Enviado pelo Watch de volta ao telemóvel após receber e processar uma notificação. */
    const val PATH_NOTIFY_ACK = "/peci/notify/ack"

    object NotifyType {
        const val WARNING = 0
        const val DANGER = 1
        /** UC1.3 Safe to cross — overlay verde com 👍. */
        const val SAFE = 2
    }
    const val PATH_TRAJECTORY_EVENT = "/peci/trajectory/event"
    const val PATH_PHONE_STATE = "/peci/phone/state"
    const val PATH_WATCH_ACTION = "/peci/watch/action"

    /** Patterns de vibração reconhecidos. */
    object VibratePattern {
        const val SHORT = 0
        const val DOUBLE = 1
        const val LONG = 2
        const val TRIPLE = 3
        const val HEARTBEAT = 4
        const val ALARM = 5
    }

    /** IDs de acções que o watch pode pedir ao telemóvel. */
    object WatchAction {
        const val START_TRAJECTORY = 0
        const val STOP_TRAJECTORY = 1
        const val AUDIO_BEEP = 2
        const val CONNECT_ALL = 3
    }

    const val PATH_IMU_STATE = "/peci/imu/state"
    const val PATH_HEARTBEAT = "/peci/heartbeat"
    const val PATH_HEART_RATE = "/peci/hr"
    const val PATH_ERROR = "/peci/error"

    const val CHANNEL_IMU = "/peci/imu/stream"

    const val DATA_PATH_CAPABILITIES = "/peci/capabilities"

    const val CAPABILITY_PHONE = "peci_phone_app"
    const val CAPABILITY_WATCH = "peci_watch_app"

    const val IMU_HEADER_MAGIC = 0x57434550
    const val IMU_HEADER_VERSION = 1
    const val IMU_HEADER_SIZE = 16
    const val IMU_SAMPLE_SIZE = 32

    const val SENSOR_MASK_ACCEL = 0x0001
    const val SENSOR_MASK_GYRO = 0x0002
    const val SENSOR_MASK_MAG = 0x0004

    /** Cabeçalho descodificado do primeiro frame do canal. */
    data class Header(
        val sensorMask: Int,
        val sampleRateHz: Int,
    )

    /** Amostra descodificada. Unidades: mg / mdps / µT×10 (alinha com ImuSample do app). */
    data class WatchImuSample(
        val timestampUs: Long,
        val axMg: Short, val ayMg: Short, val azMg: Short,
        val gxMdps: Short, val gyMdps: Short, val gzMdps: Short,
        val mxUtX10: Short, val myUtX10: Short, val mzUtX10: Short,
    )

    fun parseHeader(buf: ByteBuffer): Header? {
        if (buf.remaining() < IMU_HEADER_SIZE) return null
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        if (magic != IMU_HEADER_MAGIC) return null
        val version = buf.short.toInt() and 0xFFFF
        if (version != IMU_HEADER_VERSION) return null
        val mask = buf.short.toInt() and 0xFFFF
        val rate = buf.short.toInt() and 0xFFFF
        // 6 bytes reservados
        buf.position(buf.position() + 6)
        return Header(mask, rate)
    }

    fun parseSample(buf: ByteBuffer): WatchImuSample? {
        if (buf.remaining() < IMU_SAMPLE_SIZE) return null
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val ts = buf.long
        val ax = buf.short; val ay = buf.short; val az = buf.short
        val gx = buf.short; val gy = buf.short; val gz = buf.short
        val mx = buf.short; val my = buf.short; val mz = buf.short
        // 6 bytes reservados
        buf.position(buf.position() + 6)
        return WatchImuSample(ts, ax, ay, az, gx, gy, gz, mx, my, mz)
    }
}
