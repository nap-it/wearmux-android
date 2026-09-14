package com.example.peciwearables.integration.ble

/**
 * Estado da máquina de estados BLE para cada periférico.
 */
enum class BleDeviceState {
    DISCONNECTED,
    CONNECTING,
    DISCOVERING,
    CONFIGURING,
    READY,
    /** Handshake BLE completo: todas as 12 respostas obrigatórias recebidas. */
    CONNECTED,
    ERROR
}
