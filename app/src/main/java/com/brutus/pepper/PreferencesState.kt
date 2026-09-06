package com.brutus.pepper

/**
 * Réglages locaux de la tablette. Le choix du fournisseur et du modèle vit sur la
 * webapp du cerveau, pas ici : le robot ne décide de rien.
 */
data class PreferencesState(
    val autoEngage: Boolean = false,
    val conversationMode: Boolean = false
)
