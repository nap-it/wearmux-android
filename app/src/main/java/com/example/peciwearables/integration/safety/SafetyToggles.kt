package com.example.peciwearables.integration.safety

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Flags de UC observáveis pela UI; consultadas pelo [SafetyGates] e pelos loops dos UCs. */
class SafetyToggles {
    private val _uc1 = MutableStateFlow(false)
    val uc1: StateFlow<Boolean> = _uc1.asStateFlow()

    private val _uc1_2 = MutableStateFlow(false)
    val uc1_2: StateFlow<Boolean> = _uc1_2.asStateFlow()

    private val _uc1_4 = MutableStateFlow(false)
    val uc1_4: StateFlow<Boolean> = _uc1_4.asStateFlow()

    private val _uc1_4Strict = MutableStateFlow(false)
    val uc1_4Strict: StateFlow<Boolean> = _uc1_4Strict.asStateFlow()

    private val _uc2 = MutableStateFlow(false)
    val uc2: StateFlow<Boolean> = _uc2.asStateFlow()

    private val _uc3 = MutableStateFlow(false)
    val uc3: StateFlow<Boolean> = _uc3.asStateFlow()

    private val _uc3Incoming = MutableStateFlow(false)
    val uc3Incoming: StateFlow<Boolean> = _uc3Incoming.asStateFlow()

    private val _uc4_3 = MutableStateFlow(false)
    val uc4_3: StateFlow<Boolean> = _uc4_3.asStateFlow()

    private val _uc4_5 = MutableStateFlow(false)
    val uc4_5: StateFlow<Boolean> = _uc4_5.asStateFlow()

    fun setUc1(v: Boolean) { _uc1.value = v }
    fun setUc1_2(v: Boolean) { _uc1_2.value = v }
    fun setUc1_4(v: Boolean) { _uc1_4.value = v }
    fun setUc1_4Strict(v: Boolean) { _uc1_4Strict.value = v }
    fun setUc2(v: Boolean) { _uc2.value = v }
    fun setUc3(v: Boolean) { _uc3.value = v }
    fun setUc3Incoming(v: Boolean) { _uc3Incoming.value = v }
    fun setUc4_3(v: Boolean) { _uc4_3.value = v }
    fun setUc4_5(v: Boolean) { _uc4_5.value = v }
}
