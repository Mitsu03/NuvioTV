package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface TrackingProgressProvider {
    val providerId: TrackingProviderId
    val isAuthenticated: Flow<Boolean>
    val allProgress: Flow<List<WatchProgress>>
    val remoteProgressLoaded: Flow<Boolean>
    val nextUpSeeds: Flow<List<WatchProgress>>
    val watchedMovieIds: Flow<Set<String>>
    val ownsCompletedHistoryProjection: Boolean
        get() = false
    val clearsLocalProgressOnSelection: Boolean
        get() = false
    val watchedItems: Flow<List<WatchedItem>>
        get() = flowOf(emptyList())

    fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>>
    fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>>
    fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean>
    suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>>
    suspend fun showIdSiblings(): Map<String, Set<String>>
    fun isWatchedByVideoId(videoId: String, episode: Int): Boolean? = null
    suspend fun refresh(intent: TrackingRefreshIntent)
    suspend fun removeProgress(contentId: String, season: Int?, episode: Int?)
    fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean)
    fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?)
    fun clearOptimistic()
    fun retainsLocalProgress(contentId: String): Boolean = false
    fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean = false
    fun isHiddenFromProgress(contentId: String): Boolean

    /**
     * True when the provider's list still has this content marked as being watched.
     *
     * Next Up is seeded from watch history, which says nothing about whether the viewer
     * considers a show current. Providers that model a watchlist can answer this so a finished
     * show does not keep offering episodes. Providers without the concept answer true and
     * behave as before.
     */
    /**
     * Other ids the provider knows this content under, most likely to be served by a meta addon
     * first.
     *
     * Trackers split a franchise into one entry per season or cour and hand each its own ids, so
     * the id a row arrives under is not always one an addon can answer for. The sibling entries of
     * the same show usually carry an id that is.
     */
    fun alternateContentIds(contentId: String): List<String> = emptyList()

    fun isTrackedAsWatching(contentId: String): Boolean = true
    fun continueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? = null
    fun shouldUseAsNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean =
        progress.isCompleted()
    fun normalizeParentContentId(parentContentId: String, videoId: String?): String = parentContentId
    suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress
}

@Singleton
class TrackingProgressProviderRegistry @Inject constructor(
    providers: Set<@JvmSuppressWildcards TrackingProgressProvider>
) {
    private val providersById = providers.associateBy(TrackingProgressProvider::providerId)

    init {
        require(providersById.size == providers.size)
    }

    fun providers(): List<TrackingProgressProvider> =
        providersById.values.sortedBy { it.providerId.ordinal }

    fun provider(id: TrackingProviderId): TrackingProgressProvider? = providersById[id]
}

@Singleton
class TrackingHistoryWriterRegistry @Inject constructor(
    writers: Set<@JvmSuppressWildcards TrackingHistoryWriter>
) {
    private val writersById = writers.associateBy(TrackingHistoryWriter::providerId)

    init {
        require(writersById.size == writers.size)
    }

    fun writers(): List<TrackingHistoryWriter> =
        writersById.values.sortedBy { it.providerId.ordinal }

    fun writer(id: TrackingProviderId): TrackingHistoryWriter? = writersById[id]
}
