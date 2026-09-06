package com.brutus.pepper

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class NavigationLayoutTest {
    @Test fun `menu et rail restent au dessus des scenes hors des panneaux masquables`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/layout/activity_main.xml"))
        val root = document.documentElement
        val children = (0 until root.childNodes.length).mapNotNull {
            root.childNodes.item(it) as? Element
        }
        fun index(id: String) = children.indexOfFirst { it.getAttribute("android:id") == "@+id/$id" }
        assertTrue(index("presentationOverlay") > index("mainPanels"))
        assertTrue(index("navigationRail") > index("presentationOverlay"))
        assertTrue(index("navToggle") > index("navigationRail"))
        // Reserve a row so the menu never overlaps the home microphone switch.
        assertEquals("60dp", children[index("mainPanels")].getAttribute("android:layout_marginTop"))
        val toggle = children[index("navToggle")]
        assertEquals("TextView", toggle.tagName)
        assertEquals("56dp", toggle.getAttribute("android:layout_height"))
        assertFalse(toggle.hasAttribute("android:visibility"))
        assertEquals("@string/open_navigation", toggle.getAttribute("android:contentDescription"))
    }
}
