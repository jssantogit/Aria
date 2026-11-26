package com.music.aria.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.music.aria.BuildConfig
import com.music.aria.R
import com.music.aria.ui.theme.appleMusicKeyColor

/** Use the installed application ID, not the production ID in a dev build. */
internal fun ariaAppInfoIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

@Composable
internal fun AriaAboutScreen() {
    val context = LocalContext.current
    val label = stringResource(R.string.aria_launcher_name)
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
    }

    AriaAboutContent(
        appLabel = label,
        version = version,
        packageName = context.packageName,
        onAppInfo = {
            try {
                context.startActivity(ariaAppInfoIntent(context.packageName))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, R.string.about_app_info_unavailable, Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(context, R.string.about_app_info_unavailable, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

@Composable
internal fun AriaAboutContent(
    appLabel: String,
    version: String,
    packageName: String,
    onAppInfo: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Use the same adaptive icon that Android's application info owns.
        // AndroidView supports adaptive drawables, unlike painterResource.
        AndroidView(
            factory = { ctx -> ImageView(ctx).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
            } },
            modifier = Modifier.size(96.dp).testTag("aria-about-icon"),
        )
        Text(
            text = appLabel,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = stringResource(R.string.about_version_format, version),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    SettingsGroup(topSpacing = 20.dp) {
        SettingsRow(
            icon = Icons.Rounded.Info,
            title = stringResource(R.string.about_android_app_info),
            subtitle = packageName,
            onClick = onAppInfo,
            modifier = Modifier.testTag("aria-about-android-info"),
        )
    }

    val upstream = stringResource(R.string.about_upstream_credits)
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = appleMusicKeyColor(), textDecoration = TextDecoration.Underline),
    )
    Text(
        text = buildAnnotatedString {
            withLink(LinkAnnotation.Url("https://github.com/jssantogit/Aria", linkStyles)) {
                append("GitHub")
            }
            append("  ·  ")
            // Preserve upstream attribution, not as the installed app's identity.
            withLink(LinkAnnotation.Url("https://github.com/kushagrasinghx/BitChord", linkStyles)) {
                append(upstream)
            }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
    )
}
