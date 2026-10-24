package com.purpletv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridAdapterTest {

    @Test
    fun lastTwoRowsAreNearTheEnd() {
        assertTrue(GridAdapter.nearEnd(position = 32, count = 40, columns = 4))
        assertTrue(GridAdapter.nearEnd(position = 39, count = 40, columns = 4))
        assertFalse(GridAdapter.nearEnd(position = 31, count = 40, columns = 4))
    }

    @Test
    fun shortGridsAreAlwaysNearTheEnd() {
        assertTrue(GridAdapter.nearEnd(position = 0, count = 6, columns = 7))
    }

    @Test
    fun emptyGridIsNotNearTheEnd() {
        assertFalse(GridAdapter.nearEnd(position = 0, count = 0, columns = 4))
    }
}
