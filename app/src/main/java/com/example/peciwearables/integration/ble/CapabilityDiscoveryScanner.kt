package com.example.peciwearables.integration.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.peciwearables.integration.WearableService
import java.util.Locale
import java.util.UUID

enum class WearableKind {
    GLASSES,
    WRIST_OR_SOLE,
    UNKNOWN
}

enum class SdkDeviceType(val rawValue: Int) {
    LEFT_INSOLE(0),
    RIGHT_INSOLE(1),
    LEFT_GLOVE(2),
    RIGHT_GLOVE(3),
    GLASSES(4),
    GENERIC(5);

    companion object {
        fun fromRaw(rawValue: Int): SdkDeviceType? = values().firstOrNull { it.rawValue == rawValue }
    }
}

data class CapabilityScanCandidate(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val sdkDeviceType: SdkDeviceType?,
    val kind: WearableKind,
    val advertisedServiceUuids: Set<UUID>,
    val ipAddress: String?,
    val isWifiSecure: Boolean?,
)

@SuppressLint("MissingPermission")
class CapabilityDiscoveryScanner(private val context: Context) {

    companion object {
        private const val TAG = "CapDiscoveryScanner"
        private const val SCAN_TIMEOUT_MS = 10_000L
        /** Brilliant Sole SDK service UUID (wristband / insole). */
        private val BS_SERVICE_UUID: UUID = UUID.fromString("ea6d0000-a725-4f9b-893d-c3913e33b39f")
        /** Omi Glasses firmware service UUID (ESP32-S3). */
        private val OMI_SERVICE_UUID: UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
        private val SERVICE_DATA_UUID = ParcelUuid.fromString("00000000-0000-1000-8000-00805f9b34fb")

        /**
         * Categorias BLE "Appearance" (advertising AD type 0x19, valor >> 6) que
         * correspondem ao género que queremos mostrar: relógios, óculos e áudio.
         */
        private val RELEVANT_APPEARANCE_CATEGORIES = setOf(
            3,  // Watch
            7,  // Eye-glasses
            65, // Audio Sink (auscultadores / earbuds)
        )

        /**
         * Palavras-chave no nome que denunciam um wearable do género pretendido
         * (smart glasses, fones de ouvido, smartwatches). Heurística de último
         * recurso para devices que não expõem Class-of-Device nem Appearance.
         */
        private val GENRE_NAME_KEYWORDS = listOf(
            // óculos / smart glasses
            "glass", "frame", "omi", "eyewear", "specs", "g1",
            // fones / earbuds / headphones
            "buds", "earbud", "earphone", "headphone", "headset", "airpod",
            "pods", "freebuds", "soundcore", "jabra", "jbl", "beats", "bose", "qcy",
            // relógios / smartwatches / bands
            "watch", "band", "amazfit", "garmin", "fitbit", "miband", "gear", "wear",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private var scanner: BluetoothLeScanner? = null
    private var isScanning = false
    private var foundAnyCandidate = false

    private val discoveredAddresses = mutableSetOf<String>()
    private var onCandidate: ((CapabilityScanCandidate) -> Boolean)? = null
    private var onFinished: ((Boolean) -> Unit)? = null

    fun startScan(
        onCandidate: (CapabilityScanCandidate) -> Boolean,
        onFinished: (Boolean) -> Unit = {}
    ) {
        Log.i(TAG, "[BLE][Scan] startScan() — searching for wearables")
        if (isScanning) {
            Log.w(TAG, "[BLE][Scan] startScan() ignored: already scanning")
            WearableService.appendLog("Scan de candidatos ja em curso")
            return
        }

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: run {
            WearableService.appendLog("BluetoothAdapter indisponivel")
            onFinished(false)
            return
        }
        if (!adapter.isEnabled) {
            WearableService.appendLog("Bluetooth desligado - ativa primeiro")
            onFinished(false)
            return
        }

        val bleScanner = adapter.bluetoothLeScanner ?: run {
            WearableService.appendLog("BLE scanner indisponivel")
            onFinished(false)
            return
        }

        scanner = bleScanner
        this.onCandidate = onCandidate
        this.onFinished = onFinished
        foundAnyCandidate = false
        discoveredAddresses.clear()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        bleScanner.startScan(null, settings, scanCallback)
        isScanning = true
        WearableService.appendLog(
            "Ligacao auto: scan BLE iniciado (sem filtros rigidos de UUID)."
        )

        timeoutRunnable = Runnable {
            if (isScanning) {
                WearableService.appendLog("Scan de candidatos: timeout")
                stopScan()
            }
        }.also { handler.postDelayed(it, SCAN_TIMEOUT_MS) }
    }

    fun stopScan() {
        Log.i(TAG, "[BLE][Scan] stopScan()")
        stopScanInternal(notifyFinished = true)
    }

    private fun stopScanInternal(notifyFinished: Boolean) {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan error: ${e.message}")
        }
        isScanning = false
        timeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null

        val finished = onFinished
        val foundAny = foundAnyCandidate
        onCandidate = null
        onFinished = null

        if (notifyFinished) {
            finished?.invoke(foundAny)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address ?: return
            if (!discoveredAddresses.add(address)) return

            if (isLikelyIrrelevant(result)) return
            // Reduz o ruído: só passam wearables do género pretendido (óculos,
            // fones de ouvido, smartwatches) — os nossos devices (Omi/BS) passam
            // sempre por causa do UUID anunciado.
            if (!matchesTargetGenre(result)) return
            val candidate = buildCandidate(result) ?: return
            foundAnyCandidate = true
            val services = result.scanRecord?.serviceUuids
                ?.joinToString(",") { it.uuid.toString().substring(0, 8) }
                ?: "none"
            val mfgSummary = manufacturerSummary(result.scanRecord)
            val sdkTypeFromServiceData = parseSdkTypeFromServiceData(result.scanRecord)?.name ?: "N/A"
            WearableService.appendLog(
                "Candidato BLE: ${candidate.name} [${candidate.address}] " +
                    "tipo=${candidate.kind} rssi=${candidate.rssi} " +
                    "sdkType=${candidate.sdkDeviceType?.name ?: "N/A"} " +
                    "svc=[$services] mfg=[$mfgSummary] svcType=$sdkTypeFromServiceData"
            )

            val shouldStop = onCandidate?.invoke(candidate) == true
            if (shouldStop) {
                stopScanInternal(notifyFinished = true)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            WearableService.appendLog("Scan de candidatos falhou (erro $errorCode)")
            foundAnyCandidate = false
            stopScanInternal(notifyFinished = true)
        }
    }

    private fun isLikelyIrrelevant(result: ScanResult): Boolean {
        val record = result.scanRecord ?: return true
        val serviceUuids = record.serviceUuids?.map { it.uuid } ?: emptyList()
        if (serviceUuids.any { it == BS_SERVICE_UUID || it == OMI_SERVICE_UUID }) return false
        val name = result.device.name
        if (!name.isNullOrBlank()) return false
        val mfgData = record.manufacturerSpecificData
        for (i in 0 until mfgData.size()) {
            val payload = mfgData.valueAt(i) ?: continue
            if (payload.size >= 3) return false
        }
        return true
    }

    /**
     * Decide se o device anunciado pertence ao género que queremos listar:
     * smart glasses, fones de ouvido ou smartwatches. Combina vários sinais
     * (do mais fiável para o menos) para apanhar marcas diferentes sem inundar
     * a lista com todos os dispositivos Bluetooth à volta.
     */
    private fun matchesTargetGenre(result: ScanResult): Boolean {
        val record = result.scanRecord

        // 1. Os nossos wearables (Omi / Brilliant Sole) passam sempre.
        val serviceUuids = record?.serviceUuids?.map { it.uuid } ?: emptyList()
        if (serviceUuids.any { it == BS_SERVICE_UUID || it == OMI_SERVICE_UUID }) return true

        // 2. Class-of-Device — fiável para earbuds/relógios dual-mode (BR/EDR+LE).
        val major = runCatching { result.device.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO ||
            major == BluetoothClass.Device.Major.WEARABLE
        ) {
            return true
        }

        // 3. Appearance anunciado (relógio / óculos / áudio).
        appearanceCategory(record)?.let { category ->
            if (category in RELEVANT_APPEARANCE_CATEGORIES) return true
        }

        // 4. Heurística por nome (devices LE-only sem CoD/appearance úteis).
        val name = result.device.name
        if (!name.isNullOrBlank() && nameLooksLikeTargetGenre(name)) return true

        return false
    }

    private fun nameLooksLikeTargetGenre(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return GENRE_NAME_KEYWORDS.any { it in lower }
    }

    /** Lê a categoria do campo Appearance (AD type 0x19) do advertising raw. */
    private fun appearanceCategory(record: ScanRecord?): Int? {
        val bytes = record?.bytes ?: return null
        var index = 0
        while (index < bytes.size) {
            val length = bytes[index].toInt() and 0xFF
            if (length == 0) break
            val type = bytes.getOrNull(index + 1)?.toInt()?.and(0xFF) ?: break
            if (type == 0x19 && length >= 3 && index + 3 < bytes.size) {
                val low = bytes[index + 2].toInt() and 0xFF
                val high = bytes[index + 3].toInt() and 0xFF
                return ((high shl 8) or low) ushr 6
            }
            index += length + 1
        }
        return null
    }

    private fun buildCandidate(result: ScanResult): CapabilityScanCandidate? {
        val device = result.device
        val name = device.name ?: "(sem nome)"
        val address = device.address ?: return null
        val rssi = result.rssi

        val scanRecord = result.scanRecord
        val sdkTypeFromManufacturer = parseSdkTypeFromManufacturer(scanRecord)
        val sdkTypeFromServiceData = parseSdkTypeFromServiceData(scanRecord)
        val sdkDeviceType = sdkTypeFromManufacturer ?: sdkTypeFromServiceData

        val ipAddress = parseIpAddressFromManufacturer(scanRecord)
        val isWifiSecure = parseWifiSecureFromManufacturer(scanRecord)
        val advertisedServiceUuids = scanRecord?.serviceUuids
            ?.map { it.uuid }
            ?.toSet()
            ?: emptySet()
        val kind = inferKind(name, sdkDeviceType, advertisedServiceUuids)

        return CapabilityScanCandidate(
            device = device,
            name = name,
            address = address,
            rssi = rssi,
            sdkDeviceType = sdkDeviceType,
            kind = kind,
            advertisedServiceUuids = advertisedServiceUuids,
            ipAddress = ipAddress,
            isWifiSecure = isWifiSecure
        )
    }

    private fun inferKind(
        name: String,
        sdkDeviceType: SdkDeviceType?,
        advertisedServiceUuids: Set<UUID>,
    ): WearableKind {
        val hasGlassesLegacyLikeUuid = advertisedServiceUuids.any {
            it == OMI_SERVICE_UUID || it.toString().startsWith("19b1", ignoreCase = true)
        }
        if (hasGlassesLegacyLikeUuid) return WearableKind.GLASSES

        if (sdkDeviceType != null) {
            return when (sdkDeviceType) {
                SdkDeviceType.GLASSES -> WearableKind.GLASSES
                SdkDeviceType.LEFT_INSOLE,
                SdkDeviceType.RIGHT_INSOLE,
                SdkDeviceType.LEFT_GLOVE,
                SdkDeviceType.RIGHT_GLOVE,
                SdkDeviceType.GENERIC -> WearableKind.WRIST_OR_SOLE
            }
        }

        val lower = name.lowercase(Locale.ROOT)
        if (BS_SERVICE_UUID in advertisedServiceUuids) {
            return when {
                "omi" in lower || "glass" in lower -> WearableKind.GLASSES
                "sole" in lower || "wrist" in lower || "brilliant" in lower || "insole" in lower -> WearableKind.WRIST_OR_SOLE
                else -> WearableKind.UNKNOWN
            }
        }

        return when {
            "omi" in lower || "glass" in lower -> WearableKind.GLASSES
            "sole" in lower || "wrist" in lower || "brilliant" in lower -> WearableKind.WRIST_OR_SOLE
            else -> WearableKind.UNKNOWN
        }
    }

    private fun parseSdkTypeFromManufacturer(scanRecord: ScanRecord?): SdkDeviceType? {
        val manufacturerData = scanRecord?.manufacturerSpecificData ?: return null
        for (index in 0 until manufacturerData.size()) {
            val payload = manufacturerData.valueAt(index) ?: continue
            if (payload.size < 3) continue
            val rawType = payload[2].toInt() and 0xFF
            return SdkDeviceType.fromRaw(rawType)
        }
        return null
    }

    private fun parseIpAddressFromManufacturer(scanRecord: ScanRecord?): String? {
        val manufacturerData = scanRecord?.manufacturerSpecificData ?: return null
        for (index in 0 until manufacturerData.size()) {
            val payload = manufacturerData.valueAt(index) ?: continue
            if (payload.size < 7) continue
            val octets = (0..3).map { octetIndex ->
                payload[3 + octetIndex].toInt() and 0xFF
            }
            return octets.joinToString(".")
        }
        return null
    }

    private fun parseWifiSecureFromManufacturer(scanRecord: ScanRecord?): Boolean? {
        val manufacturerData = scanRecord?.manufacturerSpecificData ?: return null
        for (index in 0 until manufacturerData.size()) {
            val payload = manufacturerData.valueAt(index) ?: continue
            if (payload.size < 8) continue
            return (payload[7].toInt() and 0xFF) != 0
        }
        return null
    }

    private fun parseSdkTypeFromServiceData(scanRecord: ScanRecord?): SdkDeviceType? {
        val serviceData = scanRecord?.getServiceData(SERVICE_DATA_UUID) ?: return null
        if (serviceData.isEmpty()) return null
        val rawType = serviceData[0].toInt() and 0xFF
        return SdkDeviceType.fromRaw(rawType)
    }

    private fun manufacturerSummary(scanRecord: ScanRecord?): String {
        val manufacturerData = scanRecord?.manufacturerSpecificData ?: return "none"
        if (manufacturerData.size() == 0) return "none"
        return buildString {
            for (index in 0 until manufacturerData.size()) {
                if (index > 0) append(",")
                val companyId = manufacturerData.keyAt(index)
                val payload = manufacturerData.valueAt(index)
                append("0x${companyId.toString(16)}:${payload?.size ?: 0}B")
            }
        }
    }
}
