package com.nuvio.tv.data.filler

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Device-local on purpose, like the other Nuvio clients: the switch is not part of any synced profile payload. */
@Singleton
class FillerEpisodeSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean(ENABLED_KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "nuvio_filler_episode_settings"
        const val ENABLED_KEY = "enabled"
    }
}
