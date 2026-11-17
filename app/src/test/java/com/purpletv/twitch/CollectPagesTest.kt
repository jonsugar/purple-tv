package com.purpletv.twitch

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CollectPagesTest {
    @Test fun collectsEveryPageAndDeduplicatesAcrossPages() = runTest {
        val cursors = mutableListOf<String?>()
        val results = collectPages({ value: String -> value }) { cursor ->
            cursors += cursor
            when (cursor) {
                null -> Page(listOf("Zed", "Amy"), "second")
                "second" -> Page(listOf("Amy", "Bob"), "third")
                else -> Page(emptyList(), null)
            }
        }
        assertEquals(listOf("Zed", "Amy", "Bob"), results)
        assertEquals(listOf(null, "second", "third"), cursors)
    }

    @Test fun repeatedCursorFailsInsteadOfLoopingOrReturningAnIncompleteSortedList() = runTest {
        try {
            collectPages({ value: String -> value }) { Page(listOf("a"), "stuck") }
            fail("Expected IOException")
        } catch (_: IOException) { }
    }

    @Test fun laterPageFailureDoesNotPretendTheListIsComplete() = runTest {
        try {
            collectPages({ value: String -> value }) { if (it == null) Page(listOf("a"), "next") else throw IOException("offline") }
            fail("Expected IOException")
        } catch (_: IOException) { }
    }
}
