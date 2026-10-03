package com.nipuna.voicesentry

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("sentry", Context.MODE_PRIVATE)

    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) { sp.edit().putString("pin", v).apply() }

    var wakeWord: String
        get() = sp.getString("wake", "hello") ?: "hello"
        set(v) { sp.edit().putString("wake", v).apply() }

    var requireWake: Boolean
        get() = sp.getBoolean("reqwake", true)
        set(v) { sp.edit().putBoolean("reqwake", v).apply() }

    var threshold: Float
        get() = sp.getFloat("thr", 0.5f)
        set(v) { sp.edit().putFloat("thr", v).apply() }

    var strictUnlock: Boolean
        get() = sp.getBoolean("strict", true)
        set(v) { sp.edit().putBoolean("strict", v).apply() }

    var autoBoot: Boolean
        get() = sp.getBoolean("boot", false)
        set(v) { sp.edit().putBoolean("boot", v).apply() }

    var assistantOn: Boolean
        get() = sp.getBoolean("ai_on", true)
        set(v) { sp.edit().putBoolean("ai_on", v).apply() }

    var geminiKey: String
        get() = sp.getString("gkey", "") ?: ""
        set(v) { sp.edit().putString("gkey", v.trim()).apply() }

    var geminiModel: String
        get() = sp.getString("gmodel", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(v) { sp.edit().putString("gmodel", v.trim()).apply() }

    var voiceprint: FloatArray?
        get() = sp.getString("vp", null)
            ?.split(",")
            ?.mapNotNull { it.toFloatOrNull() }
            ?.toFloatArray()
            ?.takeIf { it.size > 15 }
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove("vp") else e.putString("vp", v.joinToString(","))
            e.apply()
        }
}
