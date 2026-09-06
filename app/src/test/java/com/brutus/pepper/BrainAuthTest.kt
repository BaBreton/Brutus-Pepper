package com.brutus.pepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrainAuthTest {

    private val settings = BrainSettings("machine-antenne.local:8770", "jeton-appairage")

    @Test
    fun `authenticates a media served by the brain`() {
        val header = BrainAuth.headerFor(
            "http://machine-antenne.local:8770/api/robot/image/abc123", settings
        )
        assertEquals("Authorization" to "Bearer jeton-appairage", header)
    }

    @Test
    fun `never sends the token to another host`() {
        // Une action peut désigner une URL arbitraire ; l'accompagner du jeton le
        // remettrait au site visé.
        assertNull(BrainAuth.headerFor("https://exemple.test/photo.jpg", settings))
    }

    @Test
    fun `a host that merely starts with the brain address is not the brain`() {
        // Une comparaison par préfixe de chaîne accepterait cette adresse : c'est
        // exactement la faille qu'une comparaison d'origine évite.
        assertNull(
            BrainAuth.headerFor("http://machine-antenne.local:8770.exemple.test/a.jpg", settings)
        )
    }

    @Test
    fun `a different port on the same host is not the brain`() {
        assertNull(BrainAuth.headerFor("http://machine-antenne.local:9000/a.jpg", settings))
    }

    @Test
    fun `the scheme has to match too`() {
        assertNull(BrainAuth.headerFor("https://machine-antenne.local:8770/a.jpg", settings))
    }

    @Test
    fun `ignores the case of a hand-typed address`() {
        // L'installateur saisit l'adresse lui-même ; le cerveau, lui, rend toujours
        // des URL en minuscules. La comparaison ne doit pas buter là-dessus.
        val typed = BrainSettings("http://Cerveau.Local", "jeton")
        assertEquals(
            "Authorization" to "Bearer jeton",
            BrainAuth.headerFor("http://cerveau.local:8770/api/robot/image/x", typed)
        )
    }

    @Test
    fun `an implicit port on the url side resolves to the scheme default`() {
        val onPort80 = BrainSettings("http://cerveau.local:80", "jeton")
        assertEquals(
            "Authorization" to "Bearer jeton",
            BrainAuth.headerFor("http://cerveau.local/api/robot/image/x", onPort80)
        )
    }

    @Test
    fun `sends nothing while the tablet is not paired yet`() {
        assertNull(
            BrainAuth.headerFor("http://machine-antenne.local:8770/a.jpg", BrainSettings())
        )
    }

    @Test
    fun `an unusable url is refused rather than authenticated`() {
        assertNull(BrainAuth.headerFor("pas une url", settings))
    }
}
