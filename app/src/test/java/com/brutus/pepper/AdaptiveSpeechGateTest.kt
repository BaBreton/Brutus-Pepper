package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le portier au niveau sonore, éprouvé sur les situations qui l'ont mis en défaut en
 * conditions réelles : quelqu'un qui répond sans laisser de blanc, une pièce animée,
 * un bruit isolé, et une phrase entrecoupée d'hésitations.
 */
class AdaptiveSpeechGateTest {

    /** 20 ms à 16 kHz, le pas réel de la boucle de capture. */
    private val samples = 320
    private val block = ShortArray(samples)

    private fun gate(onsetTimeoutMs: Int = 7_000, maxDurationMs: Int = 20_000) =
        AdaptiveSpeechGate(
            sampleRate = 16_000,
            onsetTimeoutMs = onsetTimeoutMs,
            maxDurationMs = maxDurationMs
        )

    private fun feed(gate: SpeechGate, times: Int, rms: Int): SpeechGate.Decision {
        var decision = SpeechGate.Decision.WAITING
        repeat(times) { decision = gate.accept(block, samples, rms) }
        return decision
    }

    @Test
    fun `entend quelqu'un qui parle des la premiere milliseconde`() {
        // LE cas qui cassait la version précédente : elle mesurait « le silence » sur
        // les 400 premières millisecondes, or c'est exactement le moment où la personne
        // répond déjà. Le seuil partait au plafond et la phrase n'était jamais entendue.
        val gate = gate()
        assertEquals(SpeechGate.Decision.SPEAKING, feed(gate, 10, rms = 2_500))
        assertTrue(gate.heardSpeech)
    }

    @Test
    fun `se referme apres la fin de phrase dans une piece calme`() {
        val gate = gate()
        feed(gate, 6, rms = 40)          // ambiance
        feed(gate, 70, rms = 2_000)      // parole, 1,4 s
        assertEquals(SpeechGate.Decision.DONE, feed(gate, 50, rms = 40))  // 1 s de silence
    }

    @Test
    fun `se referme aussi dans une piece animee`() {
        // L'ancien seuil de fermeture restait sous le brouhaha, donc le silence ne
        // s'accumulait jamais : Pepper attendait micro ouvert jusqu'au plafond.
        val gate = gate()
        feed(gate, 40, rms = 450)        // brouhaha constant
        feed(gate, 40, rms = 3_000)      // parole par-dessus
        assertEquals(SpeechGate.Decision.DONE, feed(gate, 80, rms = 450))
    }

    @Test
    fun `un bruit isole ne relance pas la capture`() {
        // Seau percé : le claquement retire un peu de silence accumulé, il ne le
        // renverse pas. Avec une remise à zéro, chaque bruit repoussait la fermeture.
        val gate = gate()
        feed(gate, 6, rms = 40)
        feed(gate, 30, rms = 2_000)
        feed(gate, 30, rms = 40)         // silence en cours d'accumulation
        gate.accept(block, samples, 3_000)  // un claquement de porte
        assertEquals(SpeechGate.Decision.DONE, feed(gate, 45, rms = 40))
    }

    @Test
    fun `une hesitation au milieu d'une phrase ne coupe pas la personne`() {
        val gate = gate()
        feed(gate, 6, rms = 40)
        feed(gate, 40, rms = 2_000)      // première partie, assez longue
        assertEquals(SpeechGate.Decision.SPEAKING, feed(gate, 30, rms = 40))  // 600 ms de pause
        assertEquals(SpeechGate.Decision.SPEAKING, feed(gate, 20, rms = 2_000))
    }

    @Test
    fun `laisse plus de temps apres une phrase tres courte`() {
        // « Oui. » — la personne est souvent en train de réfléchir à la suite.
        val gate = gate()
        feed(gate, 6, rms = 40)
        feed(gate, 8, rms = 2_000)       // 160 ms de parole
        assertEquals(SpeechGate.Decision.SPEAKING, feed(gate, 45, rms = 40))  // 900 ms
    }

    @Test
    fun `rend la main quand personne ne parle`() {
        val gate = gate(onsetTimeoutMs = 400)
        assertEquals(SpeechGate.Decision.DONE, feed(gate, 25, rms = 30))
        assertFalse(gate.heardSpeech)
    }

    @Test
    fun `un bruit de fond seul n'ouvre pas la capture`() {
        // Le fond monte, les seuils suivent : un ronronnement constant ne doit pas
        // passer pour de la parole.
        val gate = gate(onsetTimeoutMs = 2_000)
        assertEquals(SpeechGate.Decision.DONE, feed(gate, 120, rms = 600))
        assertFalse(gate.heardSpeech)
    }

    @Test
    fun `le plafond de duree finit toujours par conclure`() {
        val gate = gate(maxDurationMs = 400)
        assertEquals(SpeechGate.Decision.DONE, feed(gate, 25, rms = 2_000))
    }

    @Test
    fun `reste sur DONE une fois conclu`() {
        val gate = gate(onsetTimeoutMs = 200)
        feed(gate, 15, rms = 30)
        assertEquals(SpeechGate.Decision.DONE, gate.accept(block, samples, 5_000))
    }

    @Test
    fun `le fond sonore reste borne meme dans un vacarme`() {
        // Sans plafond, une pièce très bruyante pousserait le seuil d'ouverture si haut
        // que plus personne ne serait entendu.
        val gate = gate()
        feed(gate, 120, rms = 9_000)
        assertTrue("fond=${gate.noiseFloor}", gate.noiseFloor <= 600)
    }
}
