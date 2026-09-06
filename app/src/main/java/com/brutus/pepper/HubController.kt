package com.brutus.pepper

enum class HubScreen { HOME, ACTIONS, GAMEPAD, PROVIDER, PREFERENCES }

data class HubState(
    val screen: HubScreen = HubScreen.HOME,
    val bindings: Map<GamepadControl, BoundAction> = defaultBindings()
)

class HubController(private val store: BindingStore) {
    var state = HubState(bindings = store.load() ?: defaultBindings())
        private set

    fun navigateTo(screen: HubScreen) {
        state = state.copy(screen = screen)
    }

    fun bind(control: GamepadControl, action: BoundAction) {
        val updated = state.bindings.toMutableMap().apply { this[control] = action }
        state = state.copy(bindings = updated)
        store.save(updated)
    }
}
