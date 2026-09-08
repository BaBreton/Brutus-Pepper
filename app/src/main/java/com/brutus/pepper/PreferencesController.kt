package com.brutus.pepper

class PreferencesController(private val store: PreferencesStore) {

    var state: PreferencesState = store.load()
        private set

    /** Called whenever state changes; MainActivity wires this to react to toggle changes. */
    var onChanged: (PreferencesState) -> Unit = {}

    fun setAutoEngage(enabled: Boolean) {
        if (state.autoEngage == enabled) return
        state = state.copy(autoEngage = enabled)
        store.save(state)
        onChanged(state)
    }

    fun setReturnHome(enabled: Boolean) {
        if (state.returnHome == enabled) return
        state = state.copy(returnHome = enabled)
        store.save(state)
        onChanged(state)
    }

    fun setConversationMode(enabled: Boolean) {
        if (state.conversationMode == enabled) return
        state = state.copy(conversationMode = enabled)
        store.save(state)
        onChanged(state)
    }

}
