package com.brutus.pepper

enum class BoundAction(val label: String) {
    NONE("Aucune"),
    PUSH_TO_TALK("Push-to-talk"),
    RAISE_BOTH_ARMS("Lever les deux bras"),
    ALTERNATE_ARMS("Bras gauche / droite"),
    BONJOUR("Bonjour"),
    RESET_CONTEXT("Réinitialiser la conv."),
    POINT_RIGHT("Pointer à droite"),
    POINT_LEFT("Pointer à gauche"),
    TURN_AROUND("Tour sur soi-même (360°)"),
    ENGAGE_HUMAN("Approcher un humain")
}

enum class GamepadControl(val label: String, val shortLabel: String) {
    A("A / □ Carré",    "A"),
    B("B / ○ Rond",     "B"),
    X("X / ✕ Croix",   "X"),
    Y("Y / △ Triangle", "Y"),
    LEFT_BUMPER("LB / L1",         "LB"),
    RIGHT_BUMPER("RB / R1",        "RB"),
    LEFT_TRIGGER("LT / L2",        "LT"),
    RIGHT_TRIGGER("RT / R2",       "RT"),
    SELECT("Select / Share",       "SEL"),
    START("Start / Options",       "OPT")
}

data class GamepadEdge(val control: GamepadControl, val pressed: Boolean)

fun defaultBindings(): Map<GamepadControl, BoundAction> = GamepadControl.entries
    .associateWith { BoundAction.NONE }
    .toMutableMap()
    .apply {
        this[GamepadControl.A] = BoundAction.RAISE_BOTH_ARMS
        this[GamepadControl.B] = BoundAction.ALTERNATE_ARMS
        // X reste volontairement neutre : l'ancienne animation était peu fiable
        // et ne doit plus pouvoir être déclenchée par erreur.
        this[GamepadControl.X] = BoundAction.NONE
        this[GamepadControl.Y] = BoundAction.PUSH_TO_TALK
    }
