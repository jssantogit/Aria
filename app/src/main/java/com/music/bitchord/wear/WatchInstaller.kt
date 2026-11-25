package com.music.bitchord.wear

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Inert compatibility surface for obsolete settings routes. No discovery, pairing or installation is possible. */
object WatchInstaller {
    val watches: StateFlow<List<WatchEntry>?> = MutableStateFlow(emptyList())
    val update: StateFlow<WatchUpdateState> = MutableStateFlow(WatchUpdateState.Idle)
    val pairing: StateFlow<WatchPairingState> = MutableStateFlow(WatchPairingState.Idle)

    @Suppress("UNUSED_PARAMETER")
    fun available(context: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun refresh(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun sendUpdate(context: Context, nodeId: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun startPairing(context: Context) = Unit

    fun stopPairing() = Unit

    @Suppress("UNUSED_PARAMETER")
    fun pairAndInstall(context: Context, nodeId: String, code: String) = Unit
}
