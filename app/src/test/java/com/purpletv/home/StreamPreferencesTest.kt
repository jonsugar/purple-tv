package com.purpletv.home

import android.content.Context
import com.purpletv.twitch.StreamLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamPreferencesTest {
    @Test fun defaultsToAllAndPersistsEitherSelection() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("test_stream_language", Context.MODE_PRIVATE)
        val store = StreamPreferences(prefs)
        assertEquals(StreamLanguage.ALL, store.language.value)
        store.toggleLanguage()
        assertEquals(StreamLanguage.ENGLISH, store.language.value)
        val reopened = StreamPreferences(prefs)
        assertEquals(StreamLanguage.ENGLISH, reopened.language.value)
        reopened.toggleLanguage()
        assertEquals(StreamLanguage.ALL, StreamPreferences(prefs).language.value)
    }
}
