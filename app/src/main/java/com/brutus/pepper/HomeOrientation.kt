package com.brutus.pepper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * De combien Pepper doit pivoter pour se remettre face à l'entrée.
 *
 * Le robot suit les visiteurs du regard, et son socle tourne avec. Au bout de
 * quelques échanges il ne fait plus face à la porte, et la personne suivante arrive
 * dans son dos. On retient donc l'orientation de départ, et on la retrouve quand le
 * hall redevient calme.
 *
 * Le calcul vit ici, séparé du robot : c'est la seule partie qui peut se tromper
 * silencieusement — un signe inversé enverrait Pepper tourner dans le mauvais sens —
 * et c'est la seule qu'on puisse vérifier sans robot sous la main.
 */
object HomeOrientation {

    /**
     * L'angle de lacet d'un quaternion, en radians.
     *
     * Seule la rotation autour de l'axe vertical nous intéresse : le socle de Pepper
     * ne tangue pas. C'est la formule usuelle de conversion quaternion → lacet.
     */
    fun yawOf(x: Double, y: Double, z: Double, w: Double): Double =
        atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))

    /**
     * Ramène un angle dans [-π, π], pour que Pepper prenne toujours le chemin le plus
     * court. Sans cela, un écart de 350° le ferait tourner presque d'un tour complet
     * au lieu de 10° dans l'autre sens.
     */
    fun shortestTurn(radians: Double): Double {
        var angle = radians
        while (angle > PI) angle -= 2 * PI
        while (angle < -PI) angle += 2 * PI
        return angle
    }

    /**
     * La rotation à appliquer pour revenir à l'orientation de départ, ou `null` si
     * Pepper y est déjà à peu près.
     *
     * @param yawFromHome lacet du robot exprimé dans le repère de départ.
     * @param deadZoneRadians en deçà, on ne bouge pas : un hall vide ne doit pas
     *   provoquer un frémissement du socle toutes les trente secondes.
     */
    fun correction(yawFromHome: Double, deadZoneRadians: Double = DEAD_ZONE_RADIANS): Double? {
        val ecart = shortestTurn(yawFromHome)
        if (abs(ecart) < deadZoneRadians) return null
        // Le repère de départ voit le robot tourné de `ecart` : pour l'annuler, on
        // tourne d'autant dans l'autre sens.
        return shortestTurn(-ecart)
    }

    /** Cinq degrés : en dessous, l'écart ne se voit pas et le mouvement se verrait. */
    const val DEAD_ZONE_RADIANS = 5.0 * PI / 180.0
}
