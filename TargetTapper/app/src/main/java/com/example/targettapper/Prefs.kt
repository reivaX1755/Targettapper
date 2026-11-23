package com.example.targettapper

import android.content.Context

object Prefs {
    private const val FILE = "targettapper"
    private const val KEY_PACKAGE = "target_package"

    fun getPackage(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PACKAGE, null)

    fun setPackage(ctx: Context, pkg: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_PACKAGE, pkg).apply()
    }
}
