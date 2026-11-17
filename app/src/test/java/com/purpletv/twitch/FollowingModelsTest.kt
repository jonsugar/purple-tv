package com.purpletv.twitch

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class FollowingModelsTest {
    @Test fun channelSortIsCaseInsensitiveAndRemovesDuplicateIds() {
        val z = FollowedChannel("1", "zed", "Zed", "")
        val a = FollowedChannel("2", "amy", "amy", "")
        val b = FollowedChannel("3", "bob", "Bob", "")
        assertEquals(listOf(a, b, z), sortedChannels(listOf(z, a, b, a)))
    }

    @Test fun timeWindowsAreRollingUtcAndAllTimeHasNoBoundaries() {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val now = 1_700_000_000_000L
            assertEquals(ClipWindow("2023-11-13T22:13:20Z", "2023-11-14T22:13:20Z"), clipWindow(ClipPeriod.DAY, now))
            assertEquals("2023-11-07T22:13:20Z", clipWindow(ClipPeriod.WEEK, now).start)
            assertEquals("2023-10-15T22:13:20Z", clipWindow(ClipPeriod.MONTH, now).start)
            assertEquals(ClipWindow(null, null), clipWindow(ClipPeriod.ALL, now))
        } finally { TimeZone.setDefault(old) }
    }

    @Test fun parsesAndFormatsRecordingDurations() {
        assertEquals(7384L, durationSeconds("2h3m4s"))
        assertEquals(64L, durationSeconds("1m4s"))
        assertEquals(0L, durationSeconds(""))
        assertEquals("2:03:04", durationText(7384))
        assertEquals("1:04", durationText(64))
        assertEquals("0:00", durationText(-5))
    }

    @Test fun parsesFollowedChannelAndRecordingResponses() {
        val follows = HelixParser.followedChannels("""{"data":[{"broadcaster_id":"7","broadcaster_login":"amy","broadcaster_name":"Amy","followed_at":"today"}],"pagination":{"cursor":"next"}}""")
        assertEquals("next", follows.cursor)
        assertEquals("Amy", follows.items.single().displayName)
        val vod = HelixParser.videos("""{"data":[{"id":"42","user_id":"7","duration":"2h3m4s","title":"A stream","view_count":12}]}""").items.single()
        assertEquals(RecordingKind.VOD, vod.kind)
        assertEquals(7384L, vod.durationSeconds)
        assertEquals("", vod.thumbnailUrl)
        val clip = HelixParser.clips("""{"data":[{"id":"slug","broadcaster_id":"7","duration":30.4,"view_count":23}]}""").items.single()
        assertEquals(RecordingKind.CLIP, clip.kind)
        assertEquals(30L, clip.durationSeconds)
        assertEquals(23, clip.views)
    }
}
