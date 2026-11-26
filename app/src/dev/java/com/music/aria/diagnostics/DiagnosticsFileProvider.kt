package com.music.aria.diagnostics

import androidx.core.content.FileProvider

/** Separate dev-only provider so diagnostics never alter the app's production FileProvider. */
class DiagnosticsFileProvider : FileProvider()
