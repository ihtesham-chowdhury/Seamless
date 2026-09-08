package com.seamless.player.util

import android.content.Context

/**
 * What this build calls itself.
 *
 * Read from the installed package rather than from `BuildConfig`. Both are the same string and the
 * constant would be cheaper, but AGP has been steadily narrowing what `BuildConfig` carries — the
 * version fields are already gone from library modules — and the package manager's answer cannot
 * be taken away. It is also the true answer: it is what the device thinks is installed.
 *
 * Empty rather than throwing where the package cannot be read at all, which should be impossible
 * and is not worth an exception if it happens.
 */
fun Context.appVersionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull().orEmpty()
