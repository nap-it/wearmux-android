package com.example.peciwearables.integration.wearable

enum class WearableConnectionState {
    DISCOVERED,
    CONNECTING,
    DISCOVERING_SERVICES,
    HANDSHAKING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED,
    ERROR,
}
