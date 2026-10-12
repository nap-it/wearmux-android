package com.wearmux.android.integration.api

data class ImuCoCoSinkConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val userId: String = "",
) {
    val isUsable: Boolean get() = enabled && host.isNotBlank()

    fun effectiveUserId(): String = userId.ifBlank { DEFAULT_USER_ID }

    companion object {
        const val DEFAULT_PORT = 8001
        const val DEFAULT_USER_ID = "wearmux"
    }
}
