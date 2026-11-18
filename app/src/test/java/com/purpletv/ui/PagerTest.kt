package com.purpletv.ui

import com.purpletv.twitch.Page
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PagerTest {

    @Test
    fun resettingAFilterCancelsItsOldResponse() = runTest {
        val first = CompletableDeferred<Page<String>>()
        var oldFilter = true
        var shown = emptyList<String>()
        val pager = Pager(this, key = { value: String -> value }, fetch = {
            if (oldFilter) first.await() else Page(listOf("new-filter"), null)
        }) { items, _ -> shown = items }
        pager.loadMore()
        runCurrent()
        pager.reset()
        oldFilter = false
        pager.loadMore()
        advanceUntilIdle()
        first.complete(Page(listOf("obsolete"), null))
        advanceUntilIdle()
        assertEquals(listOf("new-filter"), shown)
        assertEquals(listOf("new-filter"), pager.currentItems)
    }

    @Test
    fun authenticationFailureIsReportedAndCanTriggerLogin() = runTest {
        var signIn = false
        val pager = Pager<String>(this, key = { it }, fetch = { throw com.purpletv.twitch.NotLoggedInException() },
            onAuthRequired = { signIn = true }, onPage = { _, _ -> })
        pager.loadMore()
        advanceUntilIdle()
        assertTrue(signIn)
        assertTrue(pager.hasFailed)
    }

    @Test
    fun appendsPagesWithoutDuplicatesAndStopsAtTheEnd() = runTest {
        val cursors = mutableListOf<String?>()
        var shown = emptyList<String>()
        val pager = Pager(this, key = { it }, fetch = { cursor ->
            cursors += cursor
            if (cursor == null) Page(listOf("a", "b"), "c1") else Page(listOf("b", "c"), null)
        }, onPage = { items, _ -> shown = items })

        pager.loadMore(); advanceUntilIdle()
        pager.loadMore(); advanceUntilIdle()
        pager.loadMore(); advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), shown)
        assertEquals(listOf(null, "c1"), cursors)
    }

    @Test
    fun advancesPastPagesThatCannotTriggerAnotherGridLoad() = runTest {
        var shown = emptyList<String>()
        val pager = Pager(this, key = { id: String -> id }, fetch = { cursor ->
            when (cursor) {
                null -> Page(emptyList(), "second")
                "second" -> Page(listOf("a"), "third")
                "third" -> Page(listOf("a"), "fourth")
                else -> Page(listOf("b"), null)
            }
        }) { items, _ -> shown = items }

        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf("a"), shown)
        pager.loadMore(); advanceUntilIdle()
        assertEquals(listOf("a", "b"), shown)
    }

    @Test
    fun ignoresLoadsWhileOneIsRunning() = runTest {
        var calls = 0
        val pager = Pager(this, key = { it }, fetch = { calls++; Page(listOf("a"), "next") }, onPage = { _, _ -> })

        pager.loadMore()
        pager.loadMore()
        advanceUntilIdle()

        assertEquals(1, calls)
    }

    @Test
    fun reportsFailureAndKeepsWhatItHas() = runTest {
        var failNext = false
        var last = Pair(emptyList<String>(), false)
        val pager = Pager(this, key = { it }, fetch = { if (failNext) throw IOException("down") else Page(listOf("a"), "next") },
            onPage = { items, failed -> last = items to failed })

        pager.loadMore(); advanceUntilIdle()
        failNext = true
        pager.loadMore(); advanceUntilIdle()

        assertEquals(listOf("a"), last.first)
        assertTrue(last.second)
    }
}
