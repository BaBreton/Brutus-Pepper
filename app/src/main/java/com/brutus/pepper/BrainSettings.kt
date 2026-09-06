package com.brutus.pepper

/**
 * Coordonnées du cerveau. C'est tout ce que la tablette détient : une adresse et un
 * jeton d'appairage. Aucune clé de fournisseur ne vit sur le robot.
 */
data class BrainSettings(
    val baseUrl: String = "",
    val pairingToken: String = ""
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && pairingToken.isNotBlank()

    /** Adresse normalisée : on tolère une saisie sans schéma ni port, et un / final. */
    fun normalizedUrl(): String {
        var value = baseUrl.trim().removeSuffix("/")
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        val withoutScheme = value.substringAfter("://")
        if (!withoutScheme.contains(':')) value = "$value:$DEFAULT_PORT"
        return value
    }

    companion object {
        const val DEFAULT_PORT = 8770
    }
}

interface BrainSettingsStore {
    fun load(): BrainSettings
    fun save(settings: BrainSettings)
    fun clear()
}
