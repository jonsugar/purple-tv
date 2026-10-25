package com.purpletv.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NoKeyHintsTest {

    @Test
    fun noStringTellsWhichKeyToPress() {
        // The user knows the remote: no "OK per…" or "Premi…" anywhere on screen.
        val hint = Regex("""\bOK\b|\bPremi\b""", RegexOption.IGNORE_CASE)
        val strings = Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(File("src/main/res/values/strings.xml").readText())
            .map { it.groupValues[1] to it.groupValues[2] }
        assertEquals(emptyList<String>(), strings.filter { hint.containsMatchIn(it.second) }.map { it.first }.toList())
    }
}
