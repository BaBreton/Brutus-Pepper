package com.brutus.pepper

import android.content.Context

class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("pepper_prefs", Context.MODE_PRIVATE)

    fun load(): PreferencesState =
        PreferencesState(
            autoEngage       = prefs.getBoolean(KEY_AUTO_ENGAGE, false),
            conversationMode = prefs.getBoolean(KEY_CONVERSATION_MODE, false)
        )

    fun save(state: PreferencesState) {
        prefs.edit()
            .putBoolean(KEY_AUTO_ENGAGE, state.autoEngage)
            .putBoolean(KEY_CONVERSATION_MODE, state.conversationMode)
            .apply()
    }

    companion object {
        private const val KEY_AUTO_ENGAGE       = "auto_engage"
        private const val KEY_CONVERSATION_MODE = "conversation_mode"
    }
}
