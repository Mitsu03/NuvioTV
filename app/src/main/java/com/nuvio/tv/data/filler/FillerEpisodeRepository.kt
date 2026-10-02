package com.nuvio.tv.data.filler

import android.util.Log
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tags fully filler anime episodes using AnimeFillerList (animefillerlist.com).
 *
 * The tag is display-only: episode titles stored in watch progress or sent to Trakt/Simkl are never touched,
 * because those services match episodes by title. Shows the site does not list, or cannot be matched with
 * confidence, simply get no tags.
 */
@Singleton
class FillerEpisodeRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private class Cached<T>(val value: T, val expiresAtMs: Long)
    private class Resolved(val videos: List<Video>, val keys: Set<Pair<Int, Int>>, val expiresAtMs: Long)

    private val mutex = Mutex()
    private var index: Cached<List<AnimeFillerListShow>>? = null
    private val episodesBySlug = mutableMapOf<String, Cached<AnimeFillerListEpisodes?>>()
    private val resolvedByMetaId = mutableMapOf<String, Resolved>()

    /** `(season, episode)` keys of the meta's episodes that are pure filler; empty when unknown. */
    suspend fun fillerEpisodeKeys(meta: Meta): Set<Pair<Int, Int>> {
        if (meta.videos.isEmpty() || !FillerEpisodeMatcher.isAnimeCandidate(meta)) return emptySet()
        mutex.withLock {
            resolvedByMetaId[meta.id]
                ?.takeIf { it.videos == meta.videos && it.expiresAtMs > now() }
                ?.let { return it.keys }
        }
        val keys = resolve(meta)
        mutex.withLock { resolvedByMetaId[meta.id] = Resolved(meta.videos, keys, now() + RESOLVED_TTL_MS) }
        return keys
    }

    private suspend fun resolve(meta: Meta): Set<Pair<Int, Int>> {
        return try {
            val shows = FillerEpisodeMatcher.candidateShows(meta, loadIndex())
            if (shows.isEmpty()) return emptySet()
            val candidates = shows.mapNotNull { show -> loadEpisodes(show.slug)?.let { show to it } }
            val episodes = FillerEpisodeMatcher.pickShow(FillerEpisodeMatcher.releaseYear(meta), candidates)
                ?: return emptySet()
            withContext(Dispatchers.Default) {
                FillerEpisodeMatcher.fillerEpisodeKeys(meta.videos, episodes.fillerEpisodes)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Filler lookup failed for ${meta.id}: ${error.message}")
            emptySet()
        }
    }

    private suspend fun loadIndex(): List<AnimeFillerListShow> = mutex.withLock {
        index?.takeIf { it.expiresAtMs > now() }?.let { return@withLock it.value }
        val html = fetch("$BASE_URL/shows")
        val shows = withContext(Dispatchers.Default) { AnimeFillerListParser.parseShowIndex(html) }
        index = Cached(shows, now() + CACHE_TTL_MS)
        shows
    }

    private suspend fun loadEpisodes(slug: String): AnimeFillerListEpisodes? = mutex.withLock {
        episodesBySlug[slug]?.takeIf { it.expiresAtMs > now() }?.let { return@withLock it.value }
        val episodes = try {
            val html = fetch("$BASE_URL/shows/$slug")
            withContext(Dispatchers.Default) { AnimeFillerListParser.parseEpisodes(html) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "AnimeFillerList page $slug failed: ${error.message}")
            null
        }
        // A failed page is remembered briefly so a flaky connection does not refetch on every screen.
        val ttl = if (episodes != null) CACHE_TTL_MS else FAILURE_TTL_MS
        episodesBySlug[slug] = Cached(episodes, now() + ttl)
        episodes
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "text/html").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "FillerEpisodeRepo"
        const val BASE_URL = "https://www.animefillerlist.com"
        const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        const val FAILURE_TTL_MS = 10L * 60L * 1000L
        const val RESOLVED_TTL_MS = 10L * 60L * 1000L
    }
}

fun Set<Pair<Int, Int>>.isFiller(season: Int?, episode: Int?): Boolean =
    season != null && episode != null && (season to episode) in this

/** Appends the localised "[Filler]" tag, resolved once by the caller from `R.string.episode_filler_tag`. */
fun String.withFillerTag(tag: String): String = if (isBlank()) tag else "$this $tag"
