package com.purpletv.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NoKeyHintsTest {

    @Test
    fun noStringTellsWhichKeyToPress() {
        // The user knows the remote: no "OK to…", "Press…" or "Premi…" anywhere on screen.
        val hint = Regex("""\bOK\b|\bPress\b|\bPremi\b""", RegexOption.IGNORE_CASE)
        val offenders = listOf("values", "values-it").flatMap { dir ->
            Regex("""<string name="([^"]+)">([^<]*)</string>""")
                .findAll(File("src/main/res/$dir/strings.xml").readText())
                .filter { hint.containsMatchIn(it.groupValues[2]) }
                .map { "$dir/${it.groupValues[1]}" }
                .toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
