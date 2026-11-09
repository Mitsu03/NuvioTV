package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress

internal fun selectPreferredTrackingNextUpSeeds(
    candidates: List<WatchProgress>,
    preferFurthestEpisode: Boolean
): List<WatchProgress> = candidates
    .groupBy(WatchProgress::contentId)
    .mapNotNull { (_, items) ->
        selectPreferredTrackingNextUpSeed(items, preferFurthestEpisode)
    }
    .sortedWith(
        compareByDescending<WatchProgress>(WatchProgress::lastWatched)
            .thenByDescending { it.season ?: -1 }
            .thenByDescending { it.episode ?: -1 }
    )

internal fun selectPreferredTrackingNextUpSeed(
    candidates: List<WatchProgress>,
    preferFurthestEpisode: Boolean
): WatchProgress? {
    if (candidates.isEmpty()) return null
    val bestRank = candidates.minOf(::trackingNextUpSeedSourceRank)
    return candidates
        .asSequence()
        .filter { trackingNextUpSeedSourceRank(it) == bestRank }
        .maxWithOrNull(
            if (preferFurthestEpisode) {
                compareBy<WatchProgress>(
                    { it.season ?: -1 },
                    { it.episode ?: -1 },
                    WatchProgress::lastWatched
                )
            } else {
                compareBy<WatchProgress>(
                    WatchProgress::lastWatched,
                    { it.season ?: -1 },
                    { it.episode ?: -1 }
                )
            }
        )
}

private fun trackingNextUpSeedSourceRank(progress: WatchProgress): Int = when (progress.source) {
    WatchProgress.SOURCE_TRAKT_PLAYBACK -> 0
    WatchProgress.SOURCE_TRAKT_SHOW_PROGRESS -> 0
    WatchProgress.SOURCE_TRAKT_HISTORY -> 1
    WatchProgress.SOURCE_LOCAL -> 2
    else -> 4
}

/** Window either side of now in which a next episode still counts as news rather than backlog. */
internal const val NEXT_UP_NEW_RELEASE_WINDOW_MS = 60L * 24 * 60 * 60 * 1000

/**
 * Whether a series the tracker no longer lists as watching may still offer a next episode.
 *
 * Next Up is seeded from watch history and never consults the list, so a show finished years ago
 * keeps offering whatever the addon lists after the furthest episode watched. That is rarely a
 * continuation: trackers model a franchise as one entry per season, cour or arc, so "finished"
 * means finished that entry, while the addon's list runs to the end of the franchise.
 *
 * The one case worth keeping is news - a season that has just started, or the next episode of a
 * show followed weekly, which a tracker marks completed between airings. Both land inside
 * [NEXT_UP_NEW_RELEASE_WINDOW_MS] of now and after the seed was watched. Everything else is backlog
 * the viewer has already decided against, and stays out of Continue Watching.
 */
internal fun shouldSurfaceNextUpForUntrackedSeries(
    seedLastWatchedEpochMs: Long,
    releasedEpochMs: Long?,
    nowEpochMs: Long
): Boolean {
    if (releasedEpochMs == null) return false
    if (releasedEpochMs <= seedLastWatchedEpochMs) return false
    val distanceFromNowMs = if (releasedEpochMs >= nowEpochMs) {
        releasedEpochMs - nowEpochMs
    } else {
        nowEpochMs - releasedEpochMs
    }
    return distanceFromNowMs <= NEXT_UP_NEW_RELEASE_WINDOW_MS
}

/**
 * Whether Continue Watching may offer a next episode for a series.
 *
 * A tracker's list is the viewer's own statement about what they are still watching, so a series
 * it no longer lists as watching stays out - unless the next episode is news, which
 * [shouldSurfaceNextUpForUntrackedSeries] decides.
 *
 * Providers with no concept of a watchlist pass `true` for [isTrackedAsWatching] and keep the
 * behaviour they had before.
 */
internal fun shouldSurfaceNextUpForSeries(
    isTrackedAsWatching: Boolean,
    seedLastWatchedEpochMs: Long,
    releasedEpochMs: Long?,
    nowEpochMs: Long
): Boolean = isTrackedAsWatching || shouldSurfaceNextUpForUntrackedSeries(
    seedLastWatchedEpochMs = seedLastWatchedEpochMs,
    releasedEpochMs = releasedEpochMs,
    nowEpochMs = nowEpochMs
)
