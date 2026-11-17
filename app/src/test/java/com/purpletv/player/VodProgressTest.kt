package com.purpletv.player

import com.purpletv.auth.FakePrefs
import org.junit.Assert.*
import org.junit.Test

class VodProgressTest {
    @Test fun positionsSurviveStoreRecreationAndBelongToAccountAndVideo() {
        val prefs = FakePrefs()
        VodProgress(prefs).save("alice", "42", 180_000)
        val restored = VodProgress(prefs)
        assertEquals(180_000L, restored.position("alice", "42"))
        assertEquals(0L, restored.position("bob", "42"))
        assertEquals(0L, restored.position("alice", "43"))
    }

    @Test fun completionAndStartingOverRemoveProgress() {
        val progress = VodProgress(FakePrefs())
        progress.save("alice", "42", 180_000)
        progress.save("alice", "42", 200_000, completed = true)
        assertEquals(0L, progress.position("alice", "42"))
        progress.save("alice", "42", 123)
        progress.save("alice", "42", 0)
        assertEquals(0L, progress.position("alice", "42"))
    }

    @Test fun anonymousPositionsAreNotSaved() {
        val prefs = FakePrefs()
        VodProgress(prefs).save("", "42", 123)
        assertTrue(prefs.values.isEmpty())
    }
}
