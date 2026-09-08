package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HomeOrientationTest {

    /** Quaternion d'une rotation autour de l'axe vertical, comme en produit le socle. */
    private fun aroundZ(radians: Double) = listOf(0.0, 0.0, sin(radians / 2), cos(radians / 2))

    private fun yawOfTurn(radians: Double): Double {
        val (x, y, z, w) = aroundZ(radians).let { arrayOf(it[0], it[1], it[2], it[3]) }
        return HomeOrientation.yawOf(x, y, z, w)
    }

    @Test
    fun `le lacet est relu tel qu'il a ete applique`() {
        for (degres in listOf(-170.0, -90.0, -12.0, 0.0, 12.0, 90.0, 170.0)) {
            val radians = Math.toRadians(degres)
            assertEquals(degres, Math.toDegrees(yawOfTurn(radians)), 1e-6)
        }
    }

    @Test
    fun `un quaternion neutre vaut zero`() {
        assertEquals(0.0, HomeOrientation.yawOf(0.0, 0.0, 0.0, 1.0), 1e-9)
    }

    @Test
    fun `pepper prend toujours le chemin le plus court`() {
        // 350° d'écart, c'est 10° dans l'autre sens : sans cela il ferait presque un
        // tour complet devant un visiteur.
        assertEquals(-10.0, Math.toDegrees(HomeOrientation.shortestTurn(Math.toRadians(350.0))), 1e-9)
        assertEquals(10.0, Math.toDegrees(HomeOrientation.shortestTurn(Math.toRadians(-350.0))), 1e-9)
        assertEquals(90.0, Math.toDegrees(HomeOrientation.shortestTurn(Math.toRadians(90.0))), 1e-9)
    }

    @Test
    fun `la correction annule l'ecart`() {
        // Tourné de 40° vers la gauche : il doit revenir de 40° vers la droite.
        val correction = HomeOrientation.correction(Math.toRadians(40.0))!!
        assertEquals(-40.0, Math.toDegrees(correction), 1e-9)
        // Et appliquée, elle ramène bien à zéro.
        assertEquals(0.0, Math.toDegrees(HomeOrientation.shortestTurn(Math.toRadians(40.0) + correction)), 1e-9)
    }

    @Test
    fun `la correction passe aussi par le chemin le plus court`() {
        val correction = HomeOrientation.correction(Math.toRadians(200.0))!!
        // 200° d'écart = -160° ; on annule par +160°, pas par -200°.
        assertEquals(160.0, Math.toDegrees(correction), 1e-6)
    }

    @Test
    fun `un ecart insignifiant ne fait pas bouger le socle`() {
        // Un hall vide ne doit pas provoquer un frémissement toutes les trente secondes.
        assertNull(HomeOrientation.correction(Math.toRadians(0.0)))
        assertNull(HomeOrientation.correction(Math.toRadians(4.0)))
        assertNull(HomeOrientation.correction(Math.toRadians(-4.0)))
        assertEquals(-6.0, Math.toDegrees(HomeOrientation.correction(Math.toRadians(6.0))!!), 1e-6)
    }

    @Test
    fun `la zone morte vaut cinq degres`() {
        assertEquals(5.0, Math.toDegrees(HomeOrientation.DEAD_ZONE_RADIANS), 1e-9)
    }

    @Test
    fun `un aller-retour complet se recompose`() {
        // Le robot pivote de 120°, puis on lit l'écart et on le corrige : il revient.
        val ecart = yawOfTurn(Math.toRadians(120.0))
        val correction = HomeOrientation.correction(ecart)!!
        assertEquals(0.0, Math.toDegrees(HomeOrientation.shortestTurn(ecart + correction)), 1e-6)
    }
}
