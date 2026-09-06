package com.brutus.pepper

interface BindingStore {
    fun load(): Map<GamepadControl, BoundAction>?
    fun save(bindings: Map<GamepadControl, BoundAction>)
}

class InMemoryBindingStore : BindingStore {
    private var value: Map<GamepadControl, BoundAction>? = null

    override fun load(): Map<GamepadControl, BoundAction>? = value

    override fun save(bindings: Map<GamepadControl, BoundAction>) {
        value = bindings.toMap()
    }
}
