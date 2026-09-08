package com.brutus.pepper

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.builder.AnimateBuilder
import com.aldebaran.qi.sdk.builder.AnimationBuilder
import com.aldebaran.qi.sdk.builder.TransformBuilder
// « object » est un mot réservé de Kotlin : le paquet QiSDK s'écrit entre accents graves.
import com.aldebaran.qi.sdk.`object`.actuation.FreeFrame

/**
 * Retient l'orientation de départ de Pepper et sait l'y ramener.
 *
 * Couche mince au-dessus du QiSDK : le calcul d'angle vit dans [HomeOrientation], qui
 * se vérifie sans robot. Ici on ne fait que lire un repère et lancer la rotation, avec
 * le même mécanisme holonome que les gestes existants — celui-là a déjà fait ses
 * preuves sur la machine.
 */
class HomePositionController(private val qiContext: QiContext) {

    private var home: FreeFrame? = null
    private var turning: Future<Void>? = null

    /** Vrai une fois qu'une orientation de départ a été retenue. */
    val isSet: Boolean get() = home != null

    /**
     * Retient la position actuelle comme position de départ.
     *
     * Appelé automatiquement à la prise de contrôle du robot — l'installateur le pose
     * face à l'entrée, c'est la bonne référence par défaut — et à la demande depuis
     * l'écran Actions quand on l'a déplacé.
     */
    fun remember(): Boolean = runCatching {
        val actuation = qiContext.actuation
        val libre = qiContext.mapping.makeFreeFrame()
        libre.update(actuation.robotFrame(), TransformBuilder.create().fromXTranslation(0.0), 0L)
        home = libre
        Log.i(TAG, "position de départ retenue")
        true
    }.getOrElse {
        Log.w(TAG, "position de départ non retenue", it)
        false
    }

    /** L'écart de lacet avec la position de départ, ou `null` si on ne sait pas. */
    fun yawFromHome(): Double? {
        val repere = home ?: return null
        return runCatching {
            val transform = qiContext.actuation.robotFrame()
                .computeTransform(repere.frame()).transform
            val rotation = transform.rotation
            HomeOrientation.yawOf(rotation.x, rotation.y, rotation.z, rotation.w)
        }.getOrElse {
            Log.w(TAG, "écart d'orientation illisible", it)
            null
        }
    }

    /**
     * Ramène Pepper face à l'entrée. Ne fait rien s'il y est déjà, si aucune position
     * n'a été retenue, ou si une rotation est déjà en cours.
     *
     * @return vrai si une rotation a été lancée.
     */
    fun returnHome(): Boolean {
        if (turning?.isDone == false) return false
        val ecart = yawFromHome() ?: return false
        val correction = HomeOrientation.correction(ecart) ?: return false
        return runCatching {
            // Même chemin que « tourner sur soi-même » : une rotation sur place, sans
            // translation. Trois secondes, assez lent pour ne bousculer personne.
            val commande = HolonomicDriveCommand(0.0, 0.0, correction, durationSeconds = 3.0)
            val animation = AnimationBuilder.with(qiContext)
                .withTexts(commande.toAnimationText())
                .build()
            turning = AnimateBuilder.with(qiContext)
                .withAnimation(animation)
                .build()
                .async()
                .run()
            Log.i(TAG, "retour à la position de départ : %.1f°".format(Math.toDegrees(correction)))
            true
        }.getOrElse {
            Log.w(TAG, "retour à la position de départ impossible", it)
            false
        }
    }

    /** Un visiteur se présente : la rotation de confort ne doit pas lui couper la parole. */
    fun cancel() {
        turning?.requestCancellation()
        turning = null
    }

    private companion object { const val TAG = "BrutusHome" }
}
