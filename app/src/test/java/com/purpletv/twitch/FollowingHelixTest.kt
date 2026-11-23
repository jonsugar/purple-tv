package com.purpletv.twitch

import com.purpletv.auth.DeviceAuth
import com.purpletv.auth.InMemoryTokenStore
import com.purpletv.auth.signIn
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FollowingHelixTest {
    private val server = MockWebServer()
    private lateinit var api: HelixClient
    @Before fun setup() {
        server.start()
        val tokens = InMemoryTokenStore().apply { signIn("token") }
        val http = OkHttpClient()
        api = HelixClient(http, tokens, DeviceAuth(http, "client", "user:read:follows", tokens), server.url("/helix/").toString())
    }
    @After fun teardown() { server.shutdown() }

    @Test fun allFollowedChannelsAreSortedAfterTheLastPage() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[{"broadcaster_id":"1","broadcaster_name":"Zed"}],"pagination":{"cursor":"second"}}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"broadcaster_id":"2","broadcaster_name":"Amy"}]}"""))
        assertEquals(listOf("Amy", "Zed"), api.allFollowedChannels("me").map { it.displayName })
        val first = server.takeRequest().requestUrl!!
        assertEquals("/helix/channels/followed", first.encodedPath)
        assertEquals("me", first.queryParameter("user_id"))
        assertEquals("second", server.takeRequest().requestUrl!!.queryParameter("after"))
    }

    @Test fun followedLiveStreamsCanLoadBeyondTheFirstHundred() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        api.followedStreamsPage("me", "page2")
        val url = server.takeRequest().requestUrl!!
        assertEquals("/helix/streams/followed", url.encodedPath)
        assertEquals("100", url.queryParameter("first"))
        assertEquals("page2", url.queryParameter("after"))
    }

    @Test fun videosRequestOnlyPastStreamsNewestFirst() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        api.videos("channel", "next")
        val url = server.takeRequest().requestUrl!!
        assertEquals("/helix/videos", url.encodedPath)
        assertEquals("channel", url.queryParameter("user_id"))
        assertEquals("archive", url.queryParameter("type"))
        assertEquals("time", url.queryParameter("sort"))
        assertEquals("next", url.queryParameter("after"))
    }

    @Test fun clipPagesRetainTheSameTimeWindowAndAllTimeOmitsIt() = runTest {
        repeat(3) { server.enqueue(MockResponse().setBody("""{"data":[]}""")) }
        val window = clipWindow(ClipPeriod.WEEK, 1_700_000_000_000L)
        api.clips("channel", window)
        api.clips("channel", window, "next")
        api.clips("channel", clipWindow(ClipPeriod.ALL, 1_700_000_000_000L))
        val first = server.takeRequest().requestUrl!!
        val second = server.takeRequest().requestUrl!!
        val all = server.takeRequest().requestUrl!!
        assertEquals("/helix/clips", first.encodedPath)
        assertEquals(first.queryParameter("started_at"), second.queryParameter("started_at"))
        assertEquals(first.queryParameter("ended_at"), second.queryParameter("ended_at"))
        assertEquals("next", second.queryParameter("after"))
        assertNull(all.queryParameter("started_at"))
        assertNull(all.queryParameter("ended_at"))
    }
}
