package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HolonomicDriveMapperTest {
    private val mapper = HolonomicDriveMapper()

    @Test
    fun `neutral sticks stop the base`() {
        assertTrue(mapper.map(0f, 0f, 0f).isNeutral)
    }

    @Test
    fun `left stick up drives forward`() {
        assertEquals(HolonomicDriveCommand(10.0, 0.0, 0.0), mapper.map(0f, -1f, 0f))
    }

    @Test
    fun `translation speed follows stick magnitude in stable bands`() {
        assertEquals(HolonomicDriveCommand(5.0, 0.0, 0.0), mapper.map(0f, -0.6f, 0f))
        assertEquals(mapper.map(0f, -0.58f, 0f), mapper.map(0f, -0.62f, 0f))
    }

    @Test
    fun `left stick down drives backward`() {
        assertEquals(HolonomicDriveCommand(-10.0, 0.0, 0.0), mapper.map(0f, 1f, 0f))
    }

    @Test
    fun `left stick right translates right`() {
        assertEquals(HolonomicDriveCommand(0.0, -10.0, 0.0), mapper.map(1f, 0f, 0f))
    }

    @Test
    fun `right stick right rotates base right`() {
        assertEquals(HolonomicDriveCommand(0.0, 0.0, -12.0), mapper.map(0f, 0f, 1f))
    }

    @Test
    fun `yaw speed follows right stick magnitude`() {
        assertEquals(HolonomicDriveCommand(0.0, 0.0, -6.0), mapper.map(0f, 0f, 0.6f))
    }

    @Test
    fun `translation and yaw share one trajectory`() {
        assertEquals(HolonomicDriveCommand(10.0, 0.0, -12.0), mapper.map(0f, -1f, 1f))
    }

    @Test
    fun `diagonal translation keeps omnidirectional intent`() {
        assertEquals(
            HolonomicDriveCommand(7.071, -7.071, 0.0),
            mapper.map(1f, -1f, 0f)
        )
    }

    @Test
    fun `forward command is stable despite lateral axis noise`() {
        assertEquals(mapper.map(0.27f, -1f, 0f), mapper.map(0.17f, -1f, 0f))
    }

    @Test
    fun `inputs inside dead zones remain neutral`() {
        assertTrue(mapper.map(0.12f, -0.12f, 0.19f).isNeutral)
    }

    @Test
    fun `command serializes as Pepper holonomic trajectory`() {
        assertEquals(
            "[\"Holonomic\", [\"Line\", [10.0, 0.0]], -12.0, 40.0]",
            HolonomicDriveCommand(10.0, 0.0, -12.0).toAnimationText()
        )
    }
}
