package com.streamcontrols.app.ui

internal const val CHROME_PACKAGE = "com.android.chrome"

/** Prefer Chrome, but use another verified Auth Tab provider if Chrome is unavailable. */
internal fun selectAuthTabBrowser(
    findChrome: () -> String?,
    findDefault: () -> String?,
    supportsAuthTab: (String) -> Boolean
): String? {
    val chrome = runCatching(findChrome).getOrNull()?.takeIf { it == CHROME_PACKAGE }
    if (chrome != null && runCatching { supportsAuthTab(chrome) }.getOrDefault(false)) return chrome
    val fallback = runCatching(findDefault).getOrNull()
    return fallback?.takeIf { it != chrome && runCatching { supportsAuthTab(it) }.getOrDefault(false) }
}
