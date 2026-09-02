package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Player
import com.nuvio.tv.core.tracking.TrackingScrobbleAction

internal fun trackingActionForNonPlayingState(playbackState: Int): TrackingScrobbleAction? = when (playbackState) {
    Player.STATE_BUFFERING -> null
    Player.STATE_ENDED, Player.STATE_IDLE -> TrackingScrobbleAction.STOP
    else -> TrackingScrobbleAction.PAUSE
}

internal fun shouldSendPauseScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble && progressPercent in 0f..100f

internal fun shouldSendStopScrobble(
    hasActiveScrobble: Boolean,
    progressPercent: Float
): Boolean = hasActiveScrobble || progressPercent >= 80f

/**
 * A pause past the watched threshold means the episode is effectively finished: trackers
 * only write history on a stop at >= 80%, so a pause there leaves the episode unwatched.
 * The player is frequently killed before [Player.STATE_ENDED] arrives — the device sleeps
 * during the credits and takes the process with it — so the pause is the last chance to
 * report the completion.
 */
internal fun shouldEscalatePauseToCompletionStop(progressPercent: Float): Boolean =
    progressPercent >= 80f
