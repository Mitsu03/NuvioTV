package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.core.util.parseEpisodeReleaseInstant
import com.nuvio.tv.data.repository.EpisodeReleaseDateSource
import com.nuvio.tv.data.repository.SimklIdResolver
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

private const val TAG = "SimklReleaseDates"
private const val RELEASES_CACHE_TTL_MS = 6L * 60 * 60 * 1000
private const val RELEASES_FAILURE_CACHE_TTL_MS = 10L * 60 * 1000
private const val MAX_SIMKL_ENTRIES_PER_SERIES = 6

/** Same shape meta addons use for `released`, e.g. `2026-10-11T14:30:00.000Z`. */
private val UTC_RELEASE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** A Simkl entry whose episode list can be fetched: `/tv/episodes/{id}` or `/anime/episodes/{id}`. */
internal data class SimklEpisodeSource(val path: String, val simklId: Long)

/**
 * Meta addons date an episode by its television broadcast, so a series that streams ahead of the
 * broadcast is listed as unaired while it is already out. Simkl dates the first availability, so
 * when it has an earlier date for the same season and episode, that date replaces the addon's.
 */
@Singleton
class SimklEpisodeReleaseDates @Inject constructor(
    @Named("simkl") private val okHttpClient: OkHttpClient,
    private val configuration: SimklApiConfiguration,
    private val syncRepository: Lazy<SimklSyncRepository>,
    private val idResolver: Lazy<SimklIdResolver>
) : EpisodeReleaseDateSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<SimklEpisodeSource, CachedReleases>()

    override suspend fun withReleaseDates(meta: Meta): Meta {
        val now = Instant.now()
        // Only an episode the addon still lists as unaired can be wrong in a way that matters.
        if (!meta.videos.hasUpcomingEpisode(now)) return meta
        if (configuration.clientId.isBlank()) return meta

        val contentIds = listOf(meta.id).filter(::usesTvdbStyleNumbering)
        if (contentIds.isEmpty()) return meta

        return try {
            val sources = syncRepository.get().state.value.snapshot.episodeSourcesFor(contentIds)
                .ifEmpty { resolveEpisodeSources(contentIds) }
            if (sources.isEmpty()) return meta

            val releases = mutableMapOf<Pair<Int, Int>, Instant>()
            sources.forEach { source ->
                releasesFor(source, now).forEach { (key, released) ->
                    val known = releases[key]
                    if (known == null || released.isBefore(known)) releases[key] = released
                }
            }
            meta.copy(videos = meta.videos.withEarlierSimklReleases(releases))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Failed to load Simkl release dates for ${meta.id}: ${error.message}")
            meta
        }
    }

    private suspend fun releasesFor(source: SimklEpisodeSource, now: Instant): Map<Pair<Int, Int>, Instant> {
        cache[source]?.takeIf { cached -> now.toEpochMilli() < cached.expiresAtEpochMs }?.let { return it.releases }
        val releases = try {
            val url = buildSimklApiUrl(
                configuration = configuration,
                path = "/${source.path}/episodes/${source.simklId}",
                query = mapOf("extended" to "full")
            )
            httpGet(url)?.let { body -> parseSimklEpisodeReleases(json, body, isAnime = source.path == "anime") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Failed to fetch Simkl episodes ${source.path}/${source.simklId}: ${error.message}")
            null
        }
        cache[source] = CachedReleases(
            releases = releases.orEmpty(),
            expiresAtEpochMs = now.toEpochMilli() +
                if (releases == null) RELEASES_FAILURE_CACHE_TTL_MS else RELEASES_CACHE_TTL_MS
        )
        return releases.orEmpty()
    }

    /** Series the user has not added to Simkl yet. */
    private suspend fun resolveEpisodeSources(contentIds: List<String>): List<SimklEpisodeSource> {
        val imdbId = contentIds.firstNotNullOfOrNull { parseTrackingExternalIds(it).imdb } ?: return emptyList()
        val resolved = idResolver.get().resolveIds("imdb", imdbId) ?: return emptyList()
        val path = when (resolved.type) {
            "anime" -> "anime"
            "tv" -> "tv"
            else -> return emptyList()
        }
        return listOf(SimklEpisodeSource(path = path, simklId = resolved.simklId))
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        okHttpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }

    private data class CachedReleases(
        val releases: Map<Pair<Int, Int>, Instant>,
        val expiresAtEpochMs: Long
    )
}

/**
 * Simkl numbers anime episodes per entry, so only its TVDB season/episode pair lines up with the
 * numbering of IMDB, TMDB and TVDB based meta. Kitsu, MAL and similar IDs number differently.
 */
private fun usesTvdbStyleNumbering(contentId: String): Boolean {
    val ids = parseTrackingExternalIds(contentId)
    return !ids.imdb.isNullOrBlank() || ids.tmdb != null || !ids.tvdb.isNullOrBlank()
}

private fun List<Video>.hasUpcomingEpisode(now: Instant): Boolean = any { video ->
    video.season != null && video.episode != null &&
        parseEpisodeReleaseInstant(video.released)?.isAfter(now) == true
}

private fun TrackingExternalIds.sharesSeriesIdWith(other: TrackingExternalIds): Boolean =
    (!imdb.isNullOrBlank() && imdb.equals(other.imdb, ignoreCase = true)) ||
        (tmdb != null && tmdb == other.tmdb) ||
        (!tvdb.isNullOrBlank() && tvdb.equals(other.tvdb, ignoreCase = true))

/** Every anime season is its own Simkl entry sharing the franchise IDs, so several can match. */
internal fun SimklSyncSnapshot.episodeSourcesFor(contentIds: List<String>): List<SimklEpisodeSource> {
    val wanted = contentIds.map(::parseTrackingExternalIds)
    return entries
        .asSequence()
        .filter { entry -> entry.mediaType != SimklMediaType.MOVIES }
        .mapNotNull { entry ->
            val media = entry.media ?: return@mapNotNull null
            val ids = media.toTrackingExternalIds()
            if (wanted.none { it.sharesSeriesIdWith(ids) }) return@mapNotNull null
            val simklId = ids.simkl?.takeIf { it > 0L } ?: return@mapNotNull null
            SimklEpisodeSource(
                path = if (entry.mediaType == SimklMediaType.ANIME) "anime" else "tv",
                simklId = simklId
            )
        }
        .distinct()
        .sortedByDescending(SimklEpisodeSource::simklId)
        .take(MAX_SIMKL_ENTRIES_PER_SERIES)
        .toList()
}

/**
 * Maps season/episode to the release instant from a Simkl `/episodes` payload. Specials and
 * episodes without a zoned date are left out. Anime is keyed by its TVDB mapping only.
 */
internal fun parseSimklEpisodeReleases(
    json: Json,
    payload: String,
    isAnime: Boolean
): Map<Pair<Int, Int>, Instant> {
    val episodes = json.parseToJsonElement(payload) as? JsonArray ?: return emptyMap()
    val releases = mutableMapOf<Pair<Int, Int>, Instant>()
    episodes.forEach { element ->
        val episode = element as? JsonObject ?: return@forEach
        if (episode.string("type")?.lowercase() != "episode") return@forEach
        val numbering = if (isAnime) episode["tvdb"] as? JsonObject ?: return@forEach else episode
        val season = numbering.int("season")?.takeIf { it > 0 } ?: return@forEach
        val number = numbering.int("episode")?.takeIf { it > 0 } ?: return@forEach
        val released = episode.string("date")
            ?.let { date -> runCatching { OffsetDateTime.parse(date).toInstant() }.getOrNull() }
            ?: return@forEach
        val key = season to number
        val known = releases[key]
        if (known == null || released.isBefore(known)) releases[key] = released
    }
    return releases
}

internal fun List<Video>.withEarlierSimklReleases(releases: Map<Pair<Int, Int>, Instant>): List<Video> {
    if (releases.isEmpty()) return this
    return map { video ->
        val season = video.season ?: return@map video
        val episode = video.episode ?: return@map video
        val simklRelease = releases[season to episode] ?: return@map video
        val addonRelease = parseEpisodeReleaseInstant(video.released) ?: return@map video
        if (simklRelease.isBefore(addonRelease)) {
            video.copy(released = UTC_RELEASE_FORMAT.format(simklRelease))
        } else {
            video
        }
    }
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
