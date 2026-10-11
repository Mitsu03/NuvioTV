package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.Meta

/** Corrects episode release dates on a freshly loaded meta, or returns it unchanged. */
fun interface EpisodeReleaseDateSource {
    suspend fun withReleaseDates(meta: Meta): Meta

    object None : EpisodeReleaseDateSource {
        override suspend fun withReleaseDates(meta: Meta): Meta = meta
    }
}
