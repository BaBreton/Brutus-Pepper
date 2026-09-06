package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotActionCoordinatorTest {
    @Test
    fun `discrete action stops drive and resumes it after completion`() {
        val drive = FakeDrive()
        val actions = FakeRobotActions()
        val coordinator = RobotActionCoordinator(drive, actions)
        coordinator.onRobotFocusGained()
        coordinator.onGamepadConnected()

        assertTrue(coordinator.run(BoundAction.POINT_RIGHT))

        assertEquals(1, drive.stopCount)
        assertEquals(listOf(BoundAction.POINT_RIGHT), actions.started)
        assertFalse(drive.running)

        actions.complete(Result.success(Unit))

        assertTrue(drive.running)
    }

    @Test
    fun `second action is rejected while one is active`() {
        val coordinator = RobotActionCoordinator(FakeDrive(), FakeRobotActions())
        coordinator.onRobotFocusGained()

        assertTrue(coordinator.run(BoundAction.RAISE_BOTH_ARMS))
        assertFalse(coordinator.run(BoundAction.POINT_LEFT))
    }

    @Test
    fun `focus loss cancels action and guarantees stopped drive`() {
        val drive = FakeDrive()
        val actions = FakeRobotActions()
        val coordinator = RobotActionCoordinator(drive, actions)
        coordinator.onRobotFocusGained()
        coordinator.onGamepadConnected()
        coordinator.run(BoundAction.ALTERNATE_ARMS)

        coordinator.onRobotFocusLost()

        assertFalse(drive.running)
        assertEquals(1, actions.cancelCount)
        actions.complete(Result.success(Unit))
        assertFalse(drive.running)
    }

    private class FakeDrive : RobotDrive {
        var running = false
        var stopCount = 0

        override fun start() {
            running = true
        }

        override fun stop() {
            stopCount += 1
            running = false
        }
    }

    private class FakeRobotActions : RobotActions {
        val started = mutableListOf<BoundAction>()
        var cancelCount = 0
        private var completion: ((Result<Unit>) -> Unit)? = null

        override fun run(action: BoundAction, onComplete: (Result<Unit>) -> Unit) {
            started += action
            completion = onComplete
        }

        override fun cancel() {
            cancelCount += 1
        }

        fun complete(result: Result<Unit>) {
            completion?.invoke(result)
        }
    }
}
