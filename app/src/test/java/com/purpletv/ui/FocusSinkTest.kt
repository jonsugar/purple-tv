package com.purpletv.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class FocusSinkTest {
    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun railStartsWithTheFocusSink() {
        // When the focused view goes away (a page swapped out, a row recycled), Android focuses the
        // window's first focusable view. Without the sink that is the rail's first item, and the
        // rail opens for one frame before the page takes the focus back.
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/res/layout/view_nav_rail.xml"))
        val first = (0 until doc.documentElement.childNodes.length).map { doc.documentElement.childNodes.item(it) }
            .first { it is Element } as Element
        assertEquals("com.purpletv.ui.FocusSink", first.tagName)
        assertEquals("@+id/nav_focus_sink", first.getAttributeNS(android, "id"))
    }
}
