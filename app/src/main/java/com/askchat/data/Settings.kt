package com.askchat.data

import android.content.Context

/** App-private preferences (excluded from backups — see AndroidManifest). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_API, v.trim()).apply()

    /** "claude" or "openai". */
    var provider: String
        get() = prefs.getString(KEY_PROVIDER, "claude").orEmpty()
        set(v) = prefs.edit().putString(KEY_PROVIDER, v).apply()

    var openAiKey: String
        get() = prefs.getString(KEY_OPENAI, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_OPENAI, v.trim()).apply()

    var openAiModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_OPENAI_MODEL, v.trim()).apply()

    /** Your own display name as it appears in exported chats. Used to tell "sent by me" from "received". */
    var selfName: String
        get() = prefs.getString(KEY_SELF, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_SELF, v.trim()).apply()

    private companion object {
        const val KEY_API = "anthropic_api_key"
        const val KEY_SELF = "self_name"
        const val KEY_PROVIDER = "provider"
        const val KEY_OPENAI = "openai_api_key"
        const val KEY_OPENAI_MODEL = "openai_model"
    }
}
