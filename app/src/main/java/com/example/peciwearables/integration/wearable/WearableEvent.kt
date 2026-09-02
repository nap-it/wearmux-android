package com.example.peciwearables.integration.wearable

/**
 * Eventos genéricos emitidos por uma [WearableSession]. Cada adapter traduz os
 * callbacks/protocolo do dispositivo concreto para um destes tipos.
 *
 * Os formatos/codecs viajam explicitamente no evento — o consumidor não precisa
 * de saber a marca para saber descodificar.
 */
sealed interface WearableEvent {

    val timestampMs: Long

    data class ImageFrameReceived(
        val bytes: ByteArray,
        val format: ImageFormat,
        val width: Int? = null,
        val height: Int? = null,
        val orientationDeg: Int? = null,
        override val timestampMs: Long,
    ) : WearableEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageFrameReceived) return false
            return bytes.contentEquals(other.bytes) &&
                format == other.format &&
                width == other.width &&
                height == other.height &&
                orientationDeg == other.orientationDeg &&
                timestampMs == other.timestampMs
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + format.hashCode()
            result = 31 * result + (width ?: 0)
            result = 31 * result + (height ?: 0)
            result = 31 * result + (orientationDeg ?: 0)
            result = 31 * result + timestampMs.hashCode()
            return result
        }
    }

    data class AudioFrameReceived(
        val bytes: ByteArray,
        val codec: AudioCodec,
        val sampleRateHz: Int? = null,
        val channels: Int? = null,
        val seq: Long? = null,
        override val timestampMs: Long,
    ) : WearableEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioFrameReceived) return false
            return bytes.contentEquals(other.bytes) &&
                codec == other.codec &&
                sampleRateHz == other.sampleRateHz &&
                channels == other.channels &&
                seq == other.seq &&
                timestampMs == other.timestampMs
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + codec.hashCode()
            result = 31 * result + (sampleRateHz ?: 0)
            result = 31 * result + (channels ?: 0)
            result = 31 * result + (seq?.hashCode() ?: 0)
            result = 31 * result + timestampMs.hashCode()
            return result
        }
    }

    data class ImuSampleReceived(
        val accel: FloatArray,
        val gyro: FloatArray,
        val mag: FloatArray? = null,
        val quaternion: FloatArray? = null,
        override val timestampMs: Long,
    ) : WearableEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImuSampleReceived) return false
            return accel.contentEquals(other.accel) &&
                gyro.contentEquals(other.gyro) &&
                (mag?.contentEquals(other.mag) ?: (other.mag == null)) &&
                (quaternion?.contentEquals(other.quaternion) ?: (other.quaternion == null)) &&
                timestampMs == other.timestampMs
        }

        override fun hashCode(): Int {
            var result = accel.contentHashCode()
            result = 31 * result + gyro.contentHashCode()
            result = 31 * result + (mag?.contentHashCode() ?: 0)
            result = 31 * result + (quaternion?.contentHashCode() ?: 0)
            result = 31 * result + timestampMs.hashCode()
            return result
        }
    }

    data class BatteryChanged(
        val percent: Int,
        override val timestampMs: Long,
    ) : WearableEvent

    data class ConnectionChanged(
        val state: WearableConnectionState,
        val reason: String? = null,
        override val timestampMs: Long,
    ) : WearableEvent

    data class DeviceInfoReceived(
        val firmware: String? = null,
        val serial: String? = null,
        val name: String? = null,
        override val timestampMs: Long,
    ) : WearableEvent

    enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    data class DeviceLog(
        val level: LogLevel,
        val message: String,
        override val timestampMs: Long,
    ) : WearableEvent

    data class DeviceError(
        val code: String,
        val message: String,
        val cause: Throwable? = null,
        override val timestampMs: Long,
    ) : WearableEvent

    data class MlInferenceReceived(
        val label: String,
        val score: Float,
        val values: FloatArray,
        override val timestampMs: Long,
    ) : WearableEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MlInferenceReceived) return false
            return label == other.label &&
                score == other.score &&
                values.contentEquals(other.values) &&
                timestampMs == other.timestampMs
        }

        override fun hashCode(): Int {
            var result = label.hashCode()
            result = 31 * result + score.hashCode()
            result = 31 * result + values.contentHashCode()
            result = 31 * result + timestampMs.hashCode()
            return result
        }
    }
}
