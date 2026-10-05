package com.wearmux.android.integration.adapters

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
