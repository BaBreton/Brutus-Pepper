package com.brutus.pepper

import android.content.Context

class CustomKeycodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("custom_keycodes", Context.MODE_PRIVATE)

    fun load(): Map<Int, GamepadControl> =
        prefs.all.entries.mapNotNull { (key, value) ->
            val keycode = key.toIntOrNull() ?: return@mapNotNull null
            val control = runCatching { GamepadControl.valueOf(value as String) }.getOrNull()
                ?: return@mapNotNull null
            keycode to control
        }.toMap()

    /** Save a keycode→control mapping. Clears any previous mapping for the same control (no duplicates). */
    fun save(keycode: Int, control: GamepadControl) {
        prefs.edit().apply {
            // Remove any other keycode that was already mapped to this control
            prefs.all.entries
                .filter { (_, v) -> v == control.name }
                .forEach { (k, _) -> remove(k) }
            putString(keycode.toString(), control.name)
        }.apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
