package com.music.aria.ui.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Route-only arrival motion, not a second navigation host.
 *
 * Apple Music's exact tab and Settings timing is not yet traced by am-descomp.
 * This deliberately small movement is a BitChord response to abrupt page
 * switches: no outgoing copy, no two-page translucent crossfade, no new
 * destination and no animation on ordinary data/scroll recomposition.
 */
/** The secondary Library filter/sort popup has a single stable size.
 * Only its drawn content enters; dismissal and outside-tap remain immediate.
 * No Apple timing is inferred from the generic popup resource inventory. */
internal data class FrostedMenuEntranceFrame(val alpha: Float, val scale: Float)

internal fun frostedMenuEntranceFrame(progress: Float, reducedMotion: Boolean): FrostedMenuEntranceFrame {
    val p = if (reducedMotion || !progress.isFinite()) 1f else progress.coerceIn(0f, 1f)
    return FrostedMenuEntranceFrame(alpha = 0.3f + 0.7f * p, scale = 0.94f + 0.06f * p)
}

@Composable
internal fun rememberFrostedMenuEntranceModifier(reduceMotion: Boolean): Modifier {
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) progress.snapTo(1f)
        else progress.animateTo(1f, tween(durationMillis = 160, easing = FastOutSlowInEasing))
    }
    return Modifier.graphicsLayer {
        val frame = frostedMenuEntranceFrame(progress.value, reduceMotion)
        alpha = frame.alpha
        scaleX = frame.scale
        scaleY = frame.scale
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
    }
}

internal data class RouteArrivalFrame(val alpha: Float, val translationDp: Float)

/** Opt-in content-only transition for the playlist wizard.
 * Unlike pushed Settings routes, every step/subtab stays in place. */
internal enum class RouteArrivalStyle { DIRECTIONAL, STATIONARY_SOFT_FADE }

/** Slightly more visible than the 150ms root-tab fade without shifting the UI.
 * The wizard owns a single LazyColumn: do not keep old form rows alive merely
 * to crossfade, and do not animate the keyboard or host layout bounds. */
internal fun playlistWizardFadeMillis(reducedMotion: Boolean): Int =
    if (reducedMotion) 0 else 165

internal fun playlistWizardStationaryArrivalFrame(progress: Float): RouteArrivalFrame {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    return RouteArrivalFrame(
        alpha = 0.88f + 0.12f * p,
        translationDp = 0f,
    )
}

internal fun routeArrivalFrame(progress: Float, forward: Boolean): RouteArrivalFrame {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    // +0 and -0 compare differently in a Float-backed data class. At rest
    // there must be one canonical frame regardless of entry direction.
    if (p == 1f) return RouteArrivalFrame(alpha = 1f, translationDp = 0f)
    return RouteArrivalFrame(
        alpha = 0.96f + 0.04f * p,
        translationDp = (if (forward) 1f else -1f) * 12f * (1f - p),
    )
}

/**
 * Root tabs are peers, not a forward/back navigation stack. Never slide a
 * full-screen Home/Search/Library feed from either side; the apparent rocking
 * came from direction-dependent 12dp translation of AnimatedContent itself.
 *
 * A near-opaque, short arrival keeps the switch soft without exposing the
 * transparent window below an overlapping crossfade. This is BitChord motion,
 * not a measured Apple Music timing.
 */
internal fun rootTabArrivalFrame(progress: Float): RouteArrivalFrame {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    return RouteArrivalFrame(alpha = 0.975f + 0.025f * p, translationDp = 0f)
}

/**
 * The AM-DECOMP #7 static tab transaction links 150ms alpha animators, but
 * equal enter/exit fades in our full-page AnimatedContent created transparent
 * gaps and could expose a stale outgoing tab. The shared page host must never
 * show a partial old page or a black/empty intermediate frame.
 *
 * Retire the old content immediately. Bring the new page in already opaque
 * (98.5%) with a tiny linear settling fade; the independently hosted toolbar,
 * bottom navigation and player remain at 100% opacity throughout.
 */
internal fun rootTabFadeMillis(reducedMotion: Boolean): Int = if (reducedMotion) 0 else 150

internal const val PAGE_ENTRY_MIN_ALPHA = 0.985f

/** Root peers never retain stale pixels or inherit SizeTransform's spring. */
internal fun rootTabContentTransform(reducedMotion: Boolean = false): ContentTransform {
    val duration = rootTabFadeMillis(reducedMotion)
    return ContentTransform(
        targetContentEnter = if (duration == 0) EnterTransition.None else
            fadeIn(initialAlpha = PAGE_ENTRY_MIN_ALPHA,
                animationSpec = tween(duration, easing = LinearEasing)),
        initialContentExit = if (duration == 0) ExitTransition.None else
            fadeOut(animationSpec = tween(0)),
        sizeTransform = null,
    )
}

/**
 * Search is a new active surface, and the playlist creator is a pushed
 * full-page wizard. Both must replace the retiring composition atomically:
 * even a brief 150ms root-tab crossfade showed old album covers/controls over
 * Search, and the isolated 180ms builder arrival could retain Library rows.
 *
 * This is deliberately limited to these routes. Other root-tab transitions
 * and pushed-page animations remain unchanged.
 */
internal fun mainPageMustReplaceImmediately(
    previous: String,
    next: String,
    rootTabPrefix: String,
    searchTabKey: String,
): Boolean {
    if (previous == next || !searchTabKey.startsWith(rootTabPrefix)) return false
    return previous == searchTabKey || next == searchTabKey ||
        previous == "smart_playlist_builder" || next == "smart_playlist_builder"
}

/** This slot belongs to a retired destination. Content must not be drawn
 * using mutable state owned by the replacement route. Slot-local remembered
 * scroll/menu state can be retained by Compose until disposal. */
internal fun shouldDrawCurrentPageSlot(slotRoute: String, activeRoute: String): Boolean =
    slotRoute == activeRoute

/** Non-tab navigation keys (artist MBIDs, albums, playlists, etc.) are never
 * allowed to fall through into the root Library tab when details are loading. */
internal fun isKnownRootTabSlot(routeKey: String, rootTabPrefix: String, tabCount: Int): Boolean {
    if (!routeKey.startsWith(rootTabPrefix)) return false
    val tab = routeKey.removePrefix(rootTabPrefix).toIntOrNull() ?: return false
    return tab in 0 until tabCount
}

/** Both slots switch without a fade or size spring; no translucent overlap. */
internal fun immediatePageContentTransform(reducedMotion: Boolean = false): ContentTransform =
    ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = ExitTransition.None,
        sizeTransform = null,
    )

/** Only two root tabs may crossfade each other's *current* content.
 * Pushed pages and nested destinations must not show the retiring route
 * during their own initial loading frame. */
internal fun mainPageRequiresImmediateExit(
    previous: String,
    next: String,
    rootTabPrefix: String,
): Boolean = !(previous.startsWith(rootTabPrefix) && next.startsWith(rootTabPrefix))

/** Artist sub-shelves are nested destinations, not content updates. They must
 * obey the same near-opaque arrival and instant retirement as pushed pages.
 * Keeping a 0-alpha 220ms fade here reopened the blank/ghost frame fixed in
 * the root navigation host.
 */
internal fun artistShelfContentTransform(reducedMotion: Boolean): ContentTransform =
    isolatedPageContentTransform(reducedMotion)

/** Pushed pages may settle slightly, but may never start at alpha 0.
 * An alpha-0 incoming page plus a zero-duration outgoing exit used to expose
 * a completely empty host for the first frame(s), especially on cold Room load. */
internal fun isolatedPageContentTransform(reducedMotion: Boolean): ContentTransform =
    ContentTransform(
        targetContentEnter = if (reducedMotion) EnterTransition.None else
            fadeIn(initialAlpha = PAGE_ENTRY_MIN_ALPHA, animationSpec = tween(180)),
        initialContentExit = if (reducedMotion) ExitTransition.None else
            fadeOut(animationSpec = tween(0)),
        sizeTransform = null,
    )

internal fun shouldAnimateRouteArrival(
    previous: String,
    next: String,
    reduced: Boolean,
    rootTabPrefix: String? = null,
): Boolean =
    !reduced && previous != next &&
        (rootTabPrefix == null ||
            (previous.startsWith(rootTabPrefix) && next.startsWith(rootTabPrefix)))

/**
 * The current destination enters over the already-stable page background.
 * The outgoing route is not kept alive for a second crossfade. `rootTabPrefix`
 * limits the effect to root-tab-to-root-tab switches when used on MainActivity.
 */
@Composable
internal fun rememberRouteArrivalModifier(
    routeKey: String,
    reduceMotion: Boolean,
    forward: Boolean = true,
    rootTabPrefix: String? = null,
    /** Optional per-navigation direction; read against the previous route,
     * not guessed from the destination alone (Back must move backward). */
    directionForRouteChange: ((String, String) -> Boolean)? = null,
    style: RouteArrivalStyle = RouteArrivalStyle.DIRECTIONAL,
): Modifier {
    val progress = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(routeKey) }
    var arrivingForward by remember { mutableStateOf(forward) }

    LaunchedEffect(routeKey, reduceMotion) {
        // Read the old destination before advancing it. The wizard uses this
        // pair to distinguish a forward drill-down from Back and prior tabs.
        val previousRoute = previous
        val animate = shouldAnimateRouteArrival(
            previous = previousRoute,
            next = routeKey,
            reduced = reduceMotion,
            rootTabPrefix = rootTabPrefix,
        )
        previous = routeKey
        if (animate) {
            arrivingForward = directionForRouteChange?.invoke(previousRoute, routeKey) ?: forward
            progress.snapTo(0f)
            progress.animateTo(
                1f,
                animationSpec = tween(
                    durationMillis = when {
                        rootTabPrefix != null -> rootTabFadeMillis(reduceMotion)
                        style == RouteArrivalStyle.STATIONARY_SOFT_FADE ->
                            playlistWizardFadeMillis(reduceMotion)
                        else -> 185
                    },
                    easing = if (rootTabPrefix != null) LinearEasing else FastOutSlowInEasing,
                ),
            )
        } else {
            progress.snapTo(1f)
        }
    }

    return Modifier.graphicsLayer {
        val frame = when {
            rootTabPrefix != null -> rootTabArrivalFrame(progress.value)
            style == RouteArrivalStyle.STATIONARY_SOFT_FADE ->
                playlistWizardStationaryArrivalFrame(progress.value)
            else -> routeArrivalFrame(progress.value, arrivingForward)
        }
        alpha = frame.alpha
        translationX = frame.translationDp.dp.toPx()
    }
}
