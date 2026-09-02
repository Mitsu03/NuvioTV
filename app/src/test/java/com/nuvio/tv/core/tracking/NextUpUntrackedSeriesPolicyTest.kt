package com.nuvio.tv.core.tracking

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpUntrackedSeriesPolicyTest {
    private val now = Instant.parse("2026-08-25T20:00:00Z").toEpochMilli()

    @Test
    fun `finished series does not offer an episode that aired long ago`() {
        // Simkl's entry is one cour and is completed; the addon lists the whole run, so Next Up
        // offers the following cour, which aired four years ago. That is backlog.
        assertFalse(
            shouldSurfaceNextUpForUntrackedSeries(
                seedLastWatchedEpochMs = Instant.parse("2022-08-22T00:00:00Z").toEpochMilli(),
                releasedEpochMs = Instant.parse("2022-10-01T00:00:00Z").toEpochMilli(),
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `finished series still offers an episode airing tomorrow`() {
        // Followed weekly and caught up, so the tracker reads completed between airings.
        assertTrue(
            shouldSurfaceNextUpForUntrackedSeries(
                seedLastWatchedEpochMs = Instant.parse("2026-08-24T00:00:00Z").toEpochMilli(),
                releasedEpochMs = Instant.parse("2026-08-26T00:00:00Z").toEpochMilli(),
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `finished series still offers a season that started three weeks ago`() {
        assertTrue(
            shouldSurfaceNextUpForUntrackedSeries(
                seedLastWatchedEpochMs = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(),
                releasedEpochMs = Instant.parse("2026-08-04T00:00:00Z").toEpochMilli(),
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `finished series does not offer an episode older than the seed`() {
        // The addon lists an episode the tracker's entry never had, released in 2009.
        assertFalse(
            shouldSurfaceNextUpForUntrackedSeries(
                seedLastWatchedEpochMs = Instant.parse("2026-08-20T00:00:00Z").toEpochMilli(),
                releasedEpochMs = Instant.parse("2009-06-26T00:00:00Z").toEpochMilli(),
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `finished series does not offer an episode without a release date`() {
        assertFalse(
            shouldSurfaceNextUpForUntrackedSeries(
                seedLastWatchedEpochMs = 0L,
                releasedEpochMs = null,
                nowEpochMs = now
            )
        )
    }

    @Test
    fun `window is sixty days`() {
        assertEquals(60L * 24 * 60 * 60 * 1000, NEXT_UP_NEW_RELEASE_WINDOW_MS)
        assertTrue(
            shouldSurfaceNextUpForUntrackedSeries(
                seedLastWatchedEpochMs = 0L,
                releasedEpochMs = now - NEXT_UP_NEW_RELEASE_WINDOW_MS,
                nowEpochMs = now
            )
        )
        assertFalse(
            shouldSurfaceNextUpForUntrackedSeries(
                seedLastWatchedEpochMs = 0L,
                releasedEpochMs = now - NEXT_UP_NEW_RELEASE_WINDOW_MS - 1,
                nowEpochMs = now
            )
        )
    }
}
