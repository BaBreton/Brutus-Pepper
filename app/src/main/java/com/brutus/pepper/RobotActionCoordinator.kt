package com.brutus.pepper

interface RobotDrive {
    fun start()
    fun stop()
}

interface RobotActions {
    fun run(action: BoundAction, onComplete: (Result<Unit>) -> Unit)
    fun cancel()
}

class RobotActionCoordinator(
    private val drive: RobotDrive,
    private val actions: RobotActions,
    private val onActionCompleted: ((BoundAction, Result<Unit>) -> Unit)? = null,
    private val onIdle: (() -> Unit)? = null
) {
    private var focused = false
    private var gamepadConnected = false
    private var actionRunning = false

    val isIdle: Boolean get() = focused && !actionRunning

    fun onRobotFocusGained() {
        focused = true
        resumeDriveIfAllowed()
    }

    fun onRobotFocusLost() {
        focused = false
        drive.stop()
        if (actionRunning) actions.cancel()
        actionRunning = false
    }

    fun onGamepadConnected() {
        gamepadConnected = true
        resumeDriveIfAllowed()
    }

    fun onGamepadDisconnected() {
        gamepadConnected = false
        drive.stop()
    }

    fun run(action: BoundAction): Boolean {
        if (!focused || actionRunning || action == BoundAction.NONE
            || action == BoundAction.PUSH_TO_TALK || action == BoundAction.RESET_CONTEXT) {
            return false
        }
        actionRunning = true
        drive.stop()
        actions.run(action) { result ->
            actionRunning = false
            resumeDriveIfAllowed()
            onActionCompleted?.invoke(action, result)
        }
        return true
    }

    private fun resumeDriveIfAllowed() {
        if (focused && gamepadConnected && !actionRunning) drive.start()
        if (focused && !actionRunning) onIdle?.invoke()
    }
}
