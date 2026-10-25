package com.purpletv.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TranslationsTest {
    private val placeholder = Regex("""%\d+\$[sd]""")

    /** The translatable strings of one values folder, name to placeholders. */
    private fun strings(dir: String): Map<String, List<String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to placeholder.findAll(it.textContent).map { m -> m.value }.sorted().toList() }
    }

    @Test
    fun italianTranslatesEveryEnglishString() {
        assertEquals(strings("values").keys.sorted(), strings("values-it").keys.sorted())
    }

    @Test
    fun translationsKeepThePlaceholders() {
        val english = strings("values")
        val italian = strings("values-it")
        assertEquals(emptyList<String>(), english.keys.filter { italian[it] != null && italian[it] != english[it] })
    }
}
