package io.github.z3f1rr.autovol

import android.content.Context

object BuildConfigInfo {
    fun versionName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
}
