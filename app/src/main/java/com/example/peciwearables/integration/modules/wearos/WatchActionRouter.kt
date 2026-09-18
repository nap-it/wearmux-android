package com.example.peciwearables.integration.modules.wearos

import com.example.peciwearables.integration.modules.android.AudioTestEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Reage às acções iniciadas no Galaxy Watch (`watchActions` flow): iniciar/parar trajecto,
 * beep, ligar todos os wearables. Encapsula ~22 linhas que viviam no `WearableService`.
 */
class WatchActionRouter(
    private val scope: CoroutineScope,
    private val watch: WatchClient,
    private val startPhoneSensors: () -> Unit,
    private val stopPhoneSensors: () -> Unit,
    private val startAutoConnect: () -> Unit,
    private val appendLog: (String) -> Unit,
) {
    fun start() = scope.launch {
        watch.watchActions.collect { actionId ->
            when (actionId) {
                WatchProtocol.WatchAction.START_TRAJECTORY -> {
                    startPhoneSensors(); appendLog("⌚ Watch requested: start trajectory")
                }
                WatchProtocol.WatchAction.STOP_TRAJECTORY -> {
                    stopPhoneSensors(); appendLog("⌚ Watch requested: stop trajectory")
                }
                WatchProtocol.WatchAction.AUDIO_BEEP -> {
                    AudioTestEngine.playPreset(AudioTestEngine.TonePreset.BEEP_MID); appendLog("⌚ Watch requested: beep on phone")
                }
                WatchProtocol.WatchAction.CONNECT_ALL -> {
                    startAutoConnect(); appendLog("⌚ Watch requested: connect all wearables")
                }
            }
        }
    }
}
