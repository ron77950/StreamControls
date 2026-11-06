package com.streamcontrols.app.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TitleConfirmationTest {
    @Test fun staleFirstReadIsConfirmedByLaterRead() = runBlocking {
        val titles = ArrayDeque(listOf("old", "new"))
        val waits = mutableListOf<Long>()
        val result = confirmTitle("video", "channel", "new", read = { VideoTitle("video", "channel", titles.removeFirst()) }, pause = { waits.add(it) })
        assertEquals(TitleConfirmation.Confirmed, result)
        assertEquals(listOf(750L, 750L), waits)
    }

    @Test fun transientFailuresAreBoundedAndNeverCountAsSuccess() = runBlocking {
        var reads = 0
        val waits = mutableListOf<Long>()
        val result = confirmTitle("video", "channel", "new", read = { reads++; throw IOException("fake") }, pause = { waits.add(it) })
        assertEquals(TitleConfirmation.Unavailable, result)
        assertEquals(3, reads)
        assertEquals(listOf(750L, 750L, 750L), waits)
    }

    @Test fun aDifferentLatestTitleIsReturnedForReview() = runBlocking {
        var reads = 0
        val waits = mutableListOf<Long>()
        val result = confirmTitle("video", "channel", "new", read = { reads++; VideoTitle("video", "channel", "someone else's title") }, pause = { waits.add(it) })
        assertEquals(TitleConfirmation.Different("someone else's title"), result)
        assertEquals(3, reads)
        assertEquals(listOf(750L, 750L, 750L), waits)
    }

    @Test fun earlierTitleIsNotPresentedAsCurrentWhenLaterReadsFail() = runBlocking {
        var reads = 0
        val result = confirmTitle("video", "channel", "new", read = {
            if (++reads == 1) VideoTitle("video", "channel", "old") else throw IOException("fake")
        }, pause = {})
        assertEquals(TitleConfirmation.Unavailable, result)
    }

    @Test fun wrongIdentityAndAuthorizationFailuresAreNotRetried() = runBlocking {
        var reads = 0
        val wrong = confirmTitle("video", "channel", "new", read = { reads++; VideoTitle("other", "channel", "new") }, pause = {})
        assertEquals(TitleConfirmation.WrongIdentity, wrong)
        assertEquals(1, reads)
        val wrongChannel = confirmTitle("video", "channel", "new", read = { VideoTitle("video", "other-channel", "new") }, pause = {})
        assertEquals(TitleConfirmation.WrongIdentity, wrongChannel)
        reads = 0
        val rejected = confirmTitle("video", "channel", "new", read = { reads++; throw YouTubeFailure(401, "authError") }, pause = {})
        assertEquals(TitleConfirmation.AuthorizationExpired, rejected)
        assertEquals(1, reads)
    }

    @Test fun cancellationStopsConfirmation() = runBlocking {
        try {
            confirmTitle("video", "channel", "new", read = { throw CancellationException() }, pause = {})
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun cancellationDuringInitialWaitDoesNotRead() = runBlocking {
        var reads = 0
        try {
            confirmTitle("video", "channel", "new", read = { reads++; VideoTitle("video", "channel", "new") }, pause = { throw CancellationException() })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, reads)
    }

    @Test fun unexpectedReadFailureIsReportedAsUnconfirmed() = runBlocking {
        val result = confirmTitle("video", "channel", "new", read = { throw IllegalStateException("fake") }, pause = {})
        assertEquals(TitleConfirmation.Unavailable, result)
    }
}
