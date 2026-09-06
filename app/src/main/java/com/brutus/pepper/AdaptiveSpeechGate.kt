package com.brutus.pepper

/**
 * Décide quand la personne commence et finit de parler, à partir du seul niveau sonore.
 *
 * Un décodeur de parole ferait mieux ce travail, et c'est ce qui a été essayé : mesuré
 * sur la tablette de Pepper, le décodage à vocabulaire ouvert coûte 230 % du temps réel.
 * La boucle de capture restait bloquée dedans, le tampon micro débordait, et des
 * morceaux d'audio disparaissaient — la transcription revenait amputée de mots. Le mot
 * d'éveil, lui, tient, parce qu'il décode un vocabulaire de six mots : ce n'est pas
 * comparable. Ici on paie quelques opérations arithmétiques par bloc, rien de plus.
 *
 * Restait à ne pas refaire les erreurs de la première version au niveau sonore :
 *
 *  - elle se calibrait une fois, sur les 400 premières millisecondes. Or c'est
 *    justement le moment où quelqu'un répond déjà — la « mesure du silence » était
 *    prise sur de la parole, le seuil montait au plafond et la phrase suivante n'était
 *    jamais entendue. Ici le fond sonore est le **minimum glissant** des deux dernières
 *    secondes : pendant une phrase il y a toujours des creux entre les mots, et c'est
 *    eux qu'on mesure. Parler tout de suite ne fausse plus rien.
 *
 *  - elle remettait le compteur de silence à zéro dès quelques blocs forts, si bien
 *    qu'un brouhaha régulier empêchait le micro de se refermer. Ici le silence se
 *    remplit comme un seau percé : un bruit isolé retire un peu d'eau, il ne renverse
 *    pas le seau.
 *
 * Classe pure : aucune dépendance Android, donc testable sans micro.
 */
class AdaptiveSpeechGate(
    private val sampleRate: Int = PcmAudio.SAMPLE_RATE,
    /** Sans parole détectée dans ce délai, on rend la main : personne ne parlait. */
    private val onsetTimeoutMs: Int = 7_000,
    /** Plafond absolu de la prise de parole. */
    private val maxDurationMs: Int = 20_000
) : SpeechGate {

    override var heardSpeech: Boolean = false
        private set

    private var elapsedSamples = 0
    private var speechSamples = 0
    private var silenceSamples = 0
    private var loudStreak = 0
    private var speaking = false
    private var done = false

    /** Niveaux récents, pour en tirer le minimum glissant. */
    private val recent = IntArray(WINDOW_CHUNKS) { Int.MAX_VALUE }
    private var cursor = 0
    private var seen = 0

    /** Fond sonore estimé, exposé pour le journal de fin de capture. */
    var noiseFloor: Int = 0
        private set

    override fun accept(chunk: ShortArray, count: Int, rms: Int): SpeechGate.Decision {
        if (done) return SpeechGate.Decision.DONE
        elapsedSamples += count

        remember(rms)
        val open = maxOf(noiseFloor * OPEN_NUMERATOR / OPEN_DENOMINATOR, MIN_OPEN_LEVEL)
        val close = maxOf(noiseFloor * CLOSE_NUMERATOR / CLOSE_DENOMINATOR, MIN_CLOSE_LEVEL)

        if (!speaking) {
            if (rms >= open) loudStreak++ else loudStreak = 0
            if (loudStreak >= ONSET_CHUNKS) {
                speaking = true
                heardSpeech = true
                // Les blocs qui ont servi à ouvrir font partie de la parole.
                speechSamples = count * ONSET_CHUNKS
                silenceSamples = 0
            } else if (elapsedSamples >= ms(onsetTimeoutMs)) {
                return finish()
            }
        } else {
            if (rms < close) {
                silenceSamples += count
            } else {
                speechSamples += count
                // Seau percé : un bruit isolé retire un peu de silence accumulé, il ne
                // le renverse pas. C'est ce qui permet de se refermer dans une pièce
                // animée, là où une remise à zéro laissait le micro ouvert indéfiniment.
                silenceSamples = maxOf(0, silenceSamples - count * SILENCE_PENALTY)
            }
            if (silenceSamples >= ms(requiredSilenceMs())) return finish()
        }

        if (elapsedSamples >= ms(maxDurationMs)) return finish()
        return if (speaking) SpeechGate.Decision.SPEAKING else SpeechGate.Decision.WAITING
    }

    /**
     * Range le niveau du bloc et recalcule le fond sonore.
     *
     * Le minimum et non la moyenne : entre deux mots, le niveau retombe au bruit de la
     * pièce, et c'est cette valeur-là qu'on cherche. Une moyenne serait tirée vers le
     * haut par la parole elle-même.
     */
    private fun remember(rms: Int) {
        recent[cursor] = rms
        cursor = (cursor + 1) % recent.size
        if (seen < recent.size) seen++
        var lowest = Int.MAX_VALUE
        for (index in 0 until seen) if (recent[index] < lowest) lowest = recent[index]
        // Bornes : une pièce parfaitement silencieuse ne doit pas rendre la détection
        // hypersensible au moindre souffle, et une pièce bruyante ne doit pas pouvoir
        // pousser le seuil si haut qu'on n'entende plus personne.
        noiseFloor = lowest.coerceIn(MIN_FLOOR, MAX_FLOOR)
    }

    /** Après une phrase courte, on laisse davantage de temps : la personne réfléchit. */
    private fun requiredSilenceMs(): Int =
        if (speechSamples < ms(SHORT_UTTERANCE_MS)) LONG_TAIL_MS else SHORT_TAIL_MS

    private fun finish(): SpeechGate.Decision {
        done = true
        return SpeechGate.Decision.DONE
    }

    private fun ms(value: Int): Int = sampleRate * value / 1000

    override fun describe(): String =
        "niveau sonore — fond=$noiseFloor parole=$heardSpeech"

    private companion object {
        /** Deux secondes de niveaux à 20 ms le bloc. */
        const val WINDOW_CHUNKS = 100

        /** Ouvrir demande d'être franchement au-dessus du fond : 3 fois. */
        const val OPEN_NUMERATOR = 3
        const val OPEN_DENOMINATOR = 1
        /** Refermer demande de repasser sous 1,8 fois le fond : c'est l'hystérésis. */
        const val CLOSE_NUMERATOR = 9
        const val CLOSE_DENOMINATOR = 5

        const val MIN_FLOOR = 20
        /**
         * Plafond du fond sonore, et le réglage le plus délicat de cette classe.
         *
         * Quand la fenêtre ne contient que de la parole — quelqu'un qui répond sans
         * laisser un blanc — le minimum glissant vaut le niveau de la voix elle-même.
         * Sans plafond, le seuil d'ouverture se placerait au-dessus et la personne ne
         * serait jamais entendue. 600 tient parce que trois fois 600 reste sous une
         * voix ordinaire à cette distance du micro.
         *
         * La contrepartie est assumée : dans une pièce dont le brouhaha dépasse
         * durablement 1 000, le seuil de fermeture ne repasse plus au-dessus du fond et
         * c'est le plafond de durée qui conclura. Un hall d'accueil n'en est pas là ;
         * une salle des fêtes, si.
         */
        const val MAX_FLOOR = 600
        const val MIN_OPEN_LEVEL = 200
        const val MIN_CLOSE_LEVEL = 150

        /** Blocs consécutifs (20 ms) requis pour ouvrir, soit 100 ms de parole. */
        const val ONSET_CHUNKS = 5
        /** Ce qu'un bloc bruyant retire au silence déjà accumulé. */
        const val SILENCE_PENALTY = 2

        const val SHORT_UTTERANCE_MS = 1_200
        // Ces deux délais sont du silence pur ajouté à la fin de CHAQUE prise de
        // parole : ils décident à eux seuls du ressenti de vivacité. Les valeurs
        // viennent de la version que l'usage a trouvée fluide ; les rallonger « pour
        // être sûr » se paie sur chaque échange. Puisque la fermeture ne se laisse plus
        // abuser par le bruit ambiant, il n'y a plus de raison d'allonger.
        const val LONG_TAIL_MS = 1_100
        const val SHORT_TAIL_MS = 750
    }
}
