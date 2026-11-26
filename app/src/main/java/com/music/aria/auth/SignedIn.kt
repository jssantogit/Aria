package com.music.aria.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Retired account presentation compatibility. Always starts signed out. */
object SignedIn {

    @Volatile private var state: MutableStateFlow<Boolean>? = null

    fun state(context: Context): MutableStateFlow<Boolean> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(false).also { state = it }
        }
}
