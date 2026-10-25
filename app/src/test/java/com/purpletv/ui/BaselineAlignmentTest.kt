package com.purpletv.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BaselineAlignmentTest {
    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun horizontalLayoutsWithAWeightedChildDoNotAlignBaselines() {
        // To align baselines, a horizontal LinearLayout first measures its weighted children with no
        // size limit: Home's rows then laid out every row and every card at each layout pass.
        val offenders = File("src/main/res/layout").listFiles()!!.sorted().flatMap { file ->
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
            val layouts = doc.getElementsByTagName("LinearLayout")
            (0 until layouts.length).map { layouts.item(it) as Element }.filter { layout ->
                val horizontal = layout.getAttributeNS(android, "orientation").let { it.isEmpty() || it == "horizontal" }
                val weighted = (0 until layout.childNodes.length).map { layout.childNodes.item(it) }
                    .any { it is Element && it.getAttributeNS(android, "layout_weight").isNotEmpty() }
                horizontal && weighted && layout.getAttributeNS(android, "baselineAligned") != "false"
            }.map { "${file.name}:${it.getAttributeNS(android, "id").ifEmpty { "(no id)" }}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
