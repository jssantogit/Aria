package com.music.aria.data.discord

/** No inherited third-party application ID is authorized for Aria. */
object DiscordApplication {
    const val ID = ""
    val isConfigured: Boolean get() = ID.isNotBlank()
}
