package com.music.bitchord

/**
 * Four real root destinations: Home, Explore, Library and Search.
 * Radio is a disabled visual slot, not a route. Do not navigate to it.
 */
internal fun rootTabAcceptsNavigation(index: Int): Boolean = index in 0..3

/**
 * A tap on the persistent bottom bar always wins over pushed page routes,
 * including a tap on the currently selected tab. Dismiss EVERY previous page
 * owner first; only then commit the new root and perform Search's fresh entry.
 *
 * This is deliberately independent of Compose so event order and same-tab
 * behavior can be covered without instantiating the entire Activity.
 */
internal fun dispatchRootTabSelection(
    index: Int,
    dismissPushedPages: () -> Unit,
    activateRoot: (Int) -> Unit,
    focusFreshSearch: () -> Unit,
): Boolean {
    if (!rootTabAcceptsNavigation(index)) return false
    dismissPushedPages()
    activateRoot(index)
    if (index == 3) focusFreshSearch()
    return true
}
