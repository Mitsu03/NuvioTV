package com.nuvio.tv.data.filler

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Video
import kotlin.math.abs

/** One entry of AnimeFillerList's `/shows` index. */
internal data class AnimeFillerListShow(
    val slug: String,
    /** English title plus any parenthesised alternative (usually the romaji one), as published. */
    val names: List<String>,
    /** Year AnimeFillerList appends to tell remakes apart, e.g. "Hunter × Hunter (2011)". */
    val yearHint: Int?,
)

internal data class AnimeFillerListEpisodes(
    /** Absolute numbers of the episodes AnimeFillerList classifies as pure filler. */
    val fillerEpisodes: Set<Int>,
    val firstAiredYear: Int?,
)

/**
 * Parses the server-rendered pages of animefillerlist.com.
 *
 * Only rows whose type is exactly `filler` count. `mixed_canon/filler` rows are deliberately left out: those
 * episodes carry canon material and tagging them would tell people they can skip something they cannot.
 */
internal object AnimeFillerListParser {
    private val showLinkRegex = Regex("""<a href="/shows/([^"/?#]+)"[^>]*>([^<]+)</a>""")
    private val episodeRowRegex = Regex(
        """<tr class="([^"]*)" id="eps-(\d+)"[^>]*>(.*?)</tr>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val dateRegex = Regex("""<td class="Date">\s*(\d{4})-""")
    private val parentheticalRegex = Regex("""\(([^)]*)\)""")
    private val entityRegex = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|quot|apos|lt|gt|nbsp);""")

    fun parseShowIndex(html: String): List<AnimeFillerListShow> =
        showLinkRegex.findAll(html)
            .mapNotNull { match ->
                val title = decodeEntities(match.groupValues[2]).trim()
                if (title.isEmpty()) null else showFromTitle(match.groupValues[1], title)
            }
            .distinctBy { it.slug }
            .toList()

    fun showFromTitle(slug: String, title: String): AnimeFillerListShow {
        val inner = parentheticalRegex.findAll(title).map { it.groupValues[1].trim() }.toList()
        val yearHint = inner.firstNotNullOfOrNull { value -> value.toIntOrNull()?.takeIf { it in 1950..2100 } }
        val base = title.replace(parentheticalRegex, " ").trim()
        val names = (listOf(base) + inner.filter { it.toIntOrNull() == null })
            .filter { it.isNotBlank() }
        return AnimeFillerListShow(slug = slug, names = names, yearHint = yearHint)
    }

    fun parseEpisodes(html: String): AnimeFillerListEpisodes {
        val fillers = mutableSetOf<Int>()
        var firstYear: Int? = null
        episodeRowRegex.findAll(html).forEach { match ->
            val number = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (match.groupValues[1].trim().substringBefore(' ') == "filler") fillers += number
            val year = dateRegex.find(match.groupValues[3])?.groupValues?.get(1)?.toIntOrNull()
            if (year != null && (firstYear == null || year < firstYear!!)) firstYear = year
        }
        return AnimeFillerListEpisodes(fillerEpisodes = fillers, firstAiredYear = firstYear)
    }

    private fun decodeEntities(value: String): String =
        entityRegex.replace(value) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                "nbsp" -> " "
                else -> {
                    val code = if (entity.startsWith("#x")) {
                        entity.drop(2).toIntOrNull(16)
                    } else {
                        entity.drop(1).toIntOrNull()
                    }
                    code?.let { String(Character.toChars(it)) } ?: match.value
                }
            }
        }
}

internal object FillerEpisodeMatcher {
    private val yearRegex = Regex("""\d{4}""")
    private val animeIdPrefixes = listOf("kitsu:", "mal:", "anilist:", "anidb:")

    /**
     * AnimeFillerList only covers anime, so the lookup is limited to metas that are recognisably anime. Genre
     * names may be localised by TMDB ("Animação", "Animación"), hence the substring check.
     */
    fun isAnimeCandidate(meta: Meta): Boolean {
        if (meta.type == ContentType.MOVIE) return false
        val id = meta.id.lowercase()
        if (animeIdPrefixes.any { id.startsWith(it) }) return true
        return meta.genres.isEmpty() || meta.genres.any { it.contains("anim", ignoreCase = true) }
    }

    fun candidateShows(meta: Meta, index: List<AnimeFillerListShow>): List<AnimeFillerListShow> {
        val key = normalizeAnimeTitle(meta.name)
        if (key.isEmpty()) return emptyList()
        return index.filter { show -> show.names.any { normalizeAnimeTitle(it) == key } }
    }

    fun releaseYear(meta: Meta): Int? =
        (meta.releaseInfo ?: meta.released)?.let { yearRegex.find(it)?.value?.toIntOrNull() }

    /**
     * Picks the show whose first air year agrees with the meta's. Remakes share a title ("Hunter × Hunter",
     * "Berserk"), and an unrelated live-action series can share one too, so a known year has to line up.
     */
    fun pickShow(
        metaYear: Int?,
        candidates: List<Pair<AnimeFillerListShow, AnimeFillerListEpisodes>>,
    ): AnimeFillerListEpisodes? {
        if (metaYear == null) return candidates.singleOrNull()?.second
        return candidates
            .mapNotNull { (show, episodes) ->
                val year = episodes.firstAiredYear ?: show.yearHint
                when {
                    year != null -> abs(year - metaYear).takeIf { it <= 1 }?.let { it to episodes }
                    candidates.size == 1 -> Int.MAX_VALUE to episodes
                    else -> null
                }
            }
            .minByOrNull { it.first }
            ?.second
    }

    /**
     * Maps AnimeFillerList's absolute numbers onto the addon's own `(season, episode)` keys. Addons that restart
     * the count every season are flattened in order, specials excluded; addons that already number absolutely
     * (TMDB's One Piece) are taken as they are.
     */
    fun fillerEpisodeKeys(videos: List<Video>, fillerEpisodes: Set<Int>): Set<Pair<Int, Int>> {
        if (fillerEpisodes.isEmpty()) return emptySet()
        val keys = videos.mapNotNull { video ->
            val season = video.season?.takeIf { it > 0 } ?: return@mapNotNull null
            val episode = video.episode ?: return@mapNotNull null
            season to episode
        }.toSet()
        val absoluteByKey = if (usesAbsoluteEpisodeNumbering(keys)) {
            keys.associateWith { it.second }
        } else {
            keys.sortedWith(compareBy({ it.first }, { it.second }))
                .withIndex()
                .associate { (index, key) -> key to index + 1 }
        }
        return absoluteByKey.filterValues { it in fillerEpisodes }.keys
    }

    /** A season whose lowest episode number is above 1 is continuing a running count rather than restarting it. */
    private fun usesAbsoluteEpisodeNumbering(keys: Set<Pair<Int, Int>>): Boolean =
        keys.groupBy(keySelector = { it.first }, valueTransform = { it.second })
            .any { (_, episodes) -> (episodes.minOrNull() ?: 1) > 1 }
}

internal fun normalizeAnimeTitle(value: String): String {
    val folded = buildString(value.length) {
        value.lowercase().forEach { char ->
            append(
                when (char) {
                    '×' -> 'x'
                    'ā', 'á', 'à', 'â', 'ä' -> 'a'
                    'ē', 'é', 'è', 'ê', 'ë' -> 'e'
                    'ī', 'í', 'ì', 'î', 'ï' -> 'i'
                    'ō', 'ó', 'ò', 'ô', 'ö' -> 'o'
                    'ū', 'ú', 'ù', 'û', 'ü' -> 'u'
                    else -> char
                },
            )
        }
    }
    return folded.replace(nonAlphanumericRegex, " ").trim()
}

private val nonAlphanumericRegex = Regex("[^a-z0-9]+")
