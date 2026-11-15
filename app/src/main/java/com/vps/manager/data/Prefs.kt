package com.vps.manager.data

import android.content.Context

/** העדפות תצוגה פשוטות — נשמרות מיד במכשיר. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var terminalFontSize: Float
        get() = sp.getFloat(KEY_FONT, 13f)
        set(value) = sp.edit().putFloat(KEY_FONT, value.coerceIn(9f, 22f)).apply()

    var lastSearchRoot: String
        get() = sp.getString(KEY_ROOT, "/") ?: "/"
        set(value) = sp.edit().putString(KEY_ROOT, value).apply()

    companion object {
        private const val KEY_FONT = "terminal_font"
        private const val KEY_ROOT = "search_root"

        @Volatile private var instance: Prefs? = null
        fun get(context: Context): Prefs =
            instance ?: synchronized(this) { instance ?: Prefs(context).also { instance = it } }
    }
}
