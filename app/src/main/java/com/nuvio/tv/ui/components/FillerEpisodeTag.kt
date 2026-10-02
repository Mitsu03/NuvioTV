package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.filler.withFillerTag

/** Appends the "[Filler]" tag for display only; stored progress and trackers keep the plain title. */
@Composable
fun String.fillerTagged(isFiller: Boolean): String =
    if (isFiller) withFillerTag(stringResource(R.string.episode_filler_tag)) else this
