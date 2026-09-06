package com.brutus.pepper

/**
 * Décide si une URL de média mérite le jeton d'appairage.
 *
 * Le cerveau protège ses routes, y compris celle qui sert les images qu'il a
 * téléchargées. Le rendu doit donc s'authentifier — sans quoi l'écran s'ouvre,
 * la requête revient en 401 et l'image ne s'affiche jamais.
 *
 * Mais le jeton ne part que vers le cerveau. Une action peut désigner une URL
 * arbitraire, et l'envoyer avec un en-tête d'autorisation le remettrait à
 * n'importe quel site. La comparaison porte donc sur l'origine — schéma, hôte et
 * port — et non sur un simple préfixe de chaîne : « http://machine-antenne.local:8770 »
 * est un préfixe de « http://machine-antenne.local:8770.exemple.test ».
 */
object BrainAuth {

    /** En-tête à poser sur la requête, ou null si l'URL ne vise pas le cerveau. */
    fun headerFor(url: String, settings: BrainSettings): Pair<String, String>? {
        if (!settings.isComplete) return null
        val brain = origin(settings.normalizedUrl()) ?: return null
        if (origin(url) != brain) return null
        return "Authorization" to "Bearer ${settings.pairingToken}"
    }

    /** Origine en minuscules, port explicite compris. Null si l'URL est inexploitable. */
    private fun origin(url: String): String? {
        val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        val host = parsed.host?.lowercase() ?: return null
        val port = if (parsed.port != -1) parsed.port else defaultPort(scheme) ?: return null
        return "$scheme://$host:$port"
    }

    private fun defaultPort(scheme: String): Int? = when (scheme) {
        "http" -> 80
        "https" -> 443
        else -> null
    }
}
