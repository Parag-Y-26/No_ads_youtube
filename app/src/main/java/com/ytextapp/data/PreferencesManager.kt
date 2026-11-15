package com.ytextapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "yt_ext_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        val AD_BLOCK_ENABLED = booleanPreferencesKey("ad_block_enabled")
        val SPONSORBLOCK_ENABLED = booleanPreferencesKey("sponsorblock_enabled")
        val BACKGROUND_PLAY_ENABLED = booleanPreferencesKey("background_play_enabled")
        val DESKTOP_MODE_ENABLED = booleanPreferencesKey("desktop_mode_enabled")
        val DEFAULT_DOWNLOAD_FORMAT = stringPreferencesKey("default_download_format")
        val DEFAULT_DOWNLOAD_QUALITY = stringPreferencesKey("default_download_quality")
    }

    val adBlockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AD_BLOCK_ENABLED] ?: true
    }

    val sponsorBlockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[SPONSORBLOCK_ENABLED] ?: true
    }

    val backgroundPlayEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[BACKGROUND_PLAY_ENABLED] ?: false
    }

    val desktopModeEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[DESKTOP_MODE_ENABLED] ?: false
    }

    val defaultDownloadFormat: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEFAULT_DOWNLOAD_FORMAT] ?: "mp4"
    }

    val defaultDownloadQuality: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEFAULT_DOWNLOAD_QUALITY] ?: "720p"
    }

    suspend fun setAdBlockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[AD_BLOCK_ENABLED] = enabled }
    }

    suspend fun setSponsorBlockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[SPONSORBLOCK_ENABLED] = enabled }
    }

    suspend fun setBackgroundPlayEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[BACKGROUND_PLAY_ENABLED] = enabled }
    }

    suspend fun setDesktopModeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[DESKTOP_MODE_ENABLED] = enabled }
    }

    suspend fun setDefaultDownloadFormat(format: String) {
        context.dataStore.edit { prefs -> prefs[DEFAULT_DOWNLOAD_FORMAT] = format }
    }

    suspend fun setDefaultDownloadQuality(quality: String) {
        context.dataStore.edit { prefs -> prefs[DEFAULT_DOWNLOAD_QUALITY] = quality }
    }
}
