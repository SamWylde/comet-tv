package com.tdarby.comet.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tdarby.comet.engine.EngineType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "comet_settings")

/** Persisted user settings (engine choice, desktop mode, ad-block toggles later). */
class SettingsStore(context: Context) {

    private val ds = context.applicationContext.dataStore

    val engineType: Flow<EngineType> = ds.data.map { p ->
        when (p[KEY_ENGINE]) {
            EngineType.GECKO.name -> EngineType.GECKO
            else -> EngineType.WEBVIEW
        }
    }

    val desktopMode: Flow<Boolean> = ds.data.map { it[KEY_DESKTOP] ?: false }
    val blockNetwork: Flow<Boolean> = ds.data.map { it[KEY_BLOCK_NETWORK] ?: true }
    val blockCosmetic: Flow<Boolean> = ds.data.map { it[KEY_BLOCK_COSMETIC] ?: true }
    val blockPopups: Flow<Boolean> = ds.data.map { it[KEY_BLOCK_POPUPS] ?: true }
    val blockRedirects: Flow<Boolean> = ds.data.map { it[KEY_BLOCK_REDIRECTS] ?: false }
    val allowlist: Flow<Set<String>> = ds.data.map { it[KEY_ALLOWLIST] ?: emptySet() }
    val searchTemplate: Flow<String> = ds.data.map { it[KEY_SEARCH] ?: DEFAULT_SEARCH }
    val cursorSpeed: Flow<Int> = ds.data.map { it[KEY_CURSOR_SPEED] ?: DEFAULT_CURSOR_SPEED }
    val directNav: Flow<Boolean> = ds.data.map { it[KEY_DIRECT_NAV] ?: false }

    suspend fun engineTypeNow(): EngineType = engineType.first()
    suspend fun desktopModeNow(): Boolean = desktopMode.first()
    suspend fun blockNetworkNow(): Boolean = blockNetwork.first()
    suspend fun blockCosmeticNow(): Boolean = blockCosmetic.first()
    suspend fun blockPopupsNow(): Boolean = blockPopups.first()
    suspend fun blockRedirectsNow(): Boolean = blockRedirects.first()
    suspend fun allowlistNow(): Set<String> = allowlist.first()
    suspend fun searchTemplateNow(): String = searchTemplate.first()
    suspend fun cursorSpeedNow(): Int = cursorSpeed.first()
    suspend fun directNavNow(): Boolean = directNav.first()

    suspend fun setSearchTemplate(template: String) = ds.edit { it[KEY_SEARCH] = template }

    suspend fun setEngineType(type: EngineType) {
        ds.edit { it[KEY_ENGINE] = type.name }
    }

    suspend fun setDesktopMode(enabled: Boolean) {
        ds.edit { it[KEY_DESKTOP] = enabled }
    }

    suspend fun setBlockNetwork(enabled: Boolean) = ds.edit { it[KEY_BLOCK_NETWORK] = enabled }
    suspend fun setBlockCosmetic(enabled: Boolean) = ds.edit { it[KEY_BLOCK_COSMETIC] = enabled }
    suspend fun setBlockPopups(enabled: Boolean) = ds.edit { it[KEY_BLOCK_POPUPS] = enabled }
    suspend fun setBlockRedirects(enabled: Boolean) = ds.edit { it[KEY_BLOCK_REDIRECTS] = enabled }
    suspend fun setCursorSpeed(value: Int) = ds.edit { it[KEY_CURSOR_SPEED] = value }
    suspend fun setDirectNav(enabled: Boolean) = ds.edit { it[KEY_DIRECT_NAV] = enabled }

    suspend fun setSiteAllowlisted(host: String, allowlisted: Boolean) {
        ds.edit { prefs ->
            val current = (prefs[KEY_ALLOWLIST] ?: emptySet()).toMutableSet()
            if (allowlisted) current.add(host) else current.remove(host)
            prefs[KEY_ALLOWLIST] = current
        }
    }

    companion object {
        private val KEY_ENGINE = stringPreferencesKey("engine_type")
        private val KEY_DESKTOP = booleanPreferencesKey("desktop_mode")
        private val KEY_BLOCK_NETWORK = booleanPreferencesKey("block_network")
        private val KEY_BLOCK_COSMETIC = booleanPreferencesKey("block_cosmetic")
        private val KEY_BLOCK_POPUPS = booleanPreferencesKey("block_popups")
        private val KEY_BLOCK_REDIRECTS = booleanPreferencesKey("block_redirects")
        private val KEY_ALLOWLIST = stringSetPreferencesKey("allowlist_hosts")
        private val KEY_SEARCH = stringPreferencesKey("search_template")
        private val KEY_CURSOR_SPEED = intPreferencesKey("cursor_speed")
        private val KEY_DIRECT_NAV = booleanPreferencesKey("direct_nav")

        const val DEFAULT_SEARCH = "https://www.google.com/search?q=%s"
        /** Cursor speed slider value 1..5 (3 = normal). */
        const val DEFAULT_CURSOR_SPEED = 3
    }
}
