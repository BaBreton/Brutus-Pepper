package com.brutus.pepper

/** Un média de la bibliothèque du client, résolu en URL affichable par la tablette. */
data class ResolvedMedia(
    val id: String,
    val name: String,
    val kind: String,
    val url: String
)

/**
 * Résout un nom prononcé vers un média. Implémenté par [BrainClient] : la tablette ne
 * connaît que le cerveau.
 */
interface MediaResolver {
    fun resolve(query: String, onComplete: (Result<ResolvedMedia>) -> Unit)
}
