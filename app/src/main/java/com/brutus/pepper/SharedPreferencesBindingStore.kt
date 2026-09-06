package com.brutus.pepper

import android.content.Context

class SharedPreferencesBindingStore(context: Context) : BindingStore {
    private val preferences = context.getSharedPreferences("gamepad_bindings", Context.MODE_PRIVATE)

    override fun load(): Map<GamepadControl, BoundAction>? {
        if (GamepadControl.entries.none { preferences.contains(it.name) }) return null
        return GamepadControl.entries.associateWith { control ->
            runCatching {
                BoundAction.valueOf(preferences.getString(control.name, BoundAction.NONE.name)!!)
            }.getOrDefault(BoundAction.NONE)
        }
    }

    override fun save(bindings: Map<GamepadControl, BoundAction>) {
        preferences.edit().apply {
            bindings.forEach { (control, action) -> putString(control.name, action.name) }
        }.apply()
    }
}
