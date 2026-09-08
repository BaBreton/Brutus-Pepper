package com.brutus.pepper

/**
 * Réglages locaux de la tablette. Le choix du fournisseur et du modèle vit sur la
 * webapp du cerveau, pas ici : le robot ne décide de rien.
 */
data class PreferencesState(
    val autoEngage: Boolean = false,
    val conversationMode: Boolean = false,
    /**
     * Pepper se remet face à l'entrée quand le hall redevient calme. Son socle
     * tourne en suivant les visiteurs ; sans cela, la personne suivante arrive dans
     * son dos. Activé par défaut : c'est ce qu'on attend d'un robot d'accueil.
     */
    val returnHome: Boolean = true
)
