package com.guidesight.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("guidesight", Context.MODE_PRIVATE)

    var contact: String
        get() = sp.getString("contact", "") ?: ""
        set(v) { sp.edit().putString("contact", v.trim()).apply() }
    var volume: Float
        get() = sp.getFloat("volume", 1f)
        set(v) { sp.edit().putFloat("volume", v.coerceIn(0.2f, 1f)).apply() }
    var rate: Float
        get() = sp.getFloat("rate", 1f)
        set(v) { sp.edit().putFloat("rate", v.coerceIn(0.6f, 2f)).apply() }
    var hindi: Boolean
        get() = sp.getBoolean("hindi", false)
        set(v) { sp.edit().putBoolean("hindi", v).apply() }
    var vibration: Boolean
        get() = sp.getBoolean("vibration", true)
        set(v) { sp.edit().putBoolean("vibration", v).apply() }
}
