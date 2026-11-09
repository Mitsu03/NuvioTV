package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpOverlayFreshnessTest {

    @Test
    fun `an overlay for the same episode and release stays in use`() {
        val overlay = nextUp(season = 1, episode = 3, released = "2026-10-16T00:00:00.000Z")

        assertTrue(overlay.isCurrentOverlayFor(nextUp(1, 3, "2026-10-16T00:00:00.000Z")))
        assertTrue(overlay.isCurrentOverlayFor(nextUp(1, 3, "2026-10-16T09:00:00+09:00")))
    }

    @Test
    fun `a corrected release date retires the overlay`() {
        val overlay = nextUp(season = 1, episode = 3, released = "2026-10-16T00:00:00.000Z")

        assertFalse(overlay.isCurrentOverlayFor(nextUp(1, 3, "2026-10-11T14:30:00.000Z")))
    }

    @Test
    fun `another episode retires the overlay`() {
        val overlay = nextUp(season = 1, episode = 3, released = "2026-10-16T00:00:00.000Z")

        assertFalse(overlay.isCurrentOverlayFor(nextUp(1, 4, "2026-10-16T00:00:00.000Z")))
        assertFalse(overlay.isCurrentOverlayFor(nextUp(2, 3, "2026-10-16T00:00:00.000Z")))
    }

    @Test
    fun `a fresh item without a date cannot contradict the overlay`() {
        val overlay = nextUp(season = 1, episode = 3, released = "2026-10-16T00:00:00.000Z")

        assertTrue(overlay.isCurrentOverlayFor(nextUp(1, 3, null)))
        assertTrue(overlay.isCurrentOverlayFor(nextUp(1, 3, " ")))
    }

    @Test
    fun `an undated overlay gives way once the release is known`() {
        val overlay = nextUp(season = 1, episode = 3, released = null)

        assertFalse(overlay.isCurrentOverlayFor(nextUp(1, 3, "2026-10-11T14:30:00.000Z")))
        assertTrue(overlay.isCurrentOverlayFor(nextUp(1, 3, null)))
    }

    private fun nextUp(season: Int, episode: Int, released: String?) = NextUpInfo(
        contentId = "tt0000000",
        contentType = "series",
        name = "Series",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt0000000:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        thumbnail = null,
        released = released,
        lastWatched = 100L,
        sortTimestamp = 100L
    )
}
