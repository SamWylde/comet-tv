package com.tdarby.comet.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "comet_settings")

/** Persisted user settings (desktop mode, ad-block toggles, cursor options). */
class SettingsStore(context: Context) {

    private val ds = context.applicationContext.dataStore

    val desktopMode: Flow<Boolean> = ds.data.map { it[KEY_DESKTOP] ?: false }
    val blockNetwork: Flow<Boolean> = ds.data.map { it[KEY_BLOCK_NETWORK] ?: true }
    val blockCosmetic: Flow<Boolean> = ds.data.map { it[KEY_BLOCK_COSMETIC] ?: true }
    val blockPopups: Flow<Boolean> = ds.data.map { it[KEY_BLOCK_POPUPS] ?: true }
    val blockRedirects: Flow<Boolean> = ds.data.map { it[KEY_BLOCK_REDIRECTS] ?: false }
    val allowlist: Flow<Set<String>> = ds.data.map { it[KEY_ALLOWLIST] ?: emptySet() }
    val searchTemplate: Flow<String> = ds.data.map { it[KEY_SEARCH] ?: DEFAULT_SEARCH }
    val cursorSpeed: Flow<Int> = ds.data.map { it[KEY_CURSOR_SPEED] ?: DEFAULT_CURSOR_SPEED }
    val directNav: Flow<Boolean> = ds.data.map { it[KEY_DIRECT_NAV] ?: false }
    val firstRunHintShown: Flow<Boolean> = ds.data.map { it[KEY_HINT_SHOWN] ?: false }
    val siteBrowsingSettings: Flow<Map<String, SiteBrowsingSettings>> = ds.data.map {
        SiteBrowsingSettingsJson.decode(it[KEY_SITE_BROWSING_SETTINGS])
    }

    /** Read the complete startup configuration from one DataStore snapshot. */
    suspend fun snapshot(): Snapshot {
        val prefs = ds.data.first()
        return Snapshot(
            desktopMode = prefs[KEY_DESKTOP] ?: false,
            blockNetwork = prefs[KEY_BLOCK_NETWORK] ?: true,
            blockCosmetic = prefs[KEY_BLOCK_COSMETIC] ?: true,
            blockPopups = prefs[KEY_BLOCK_POPUPS] ?: true,
            blockRedirects = prefs[KEY_BLOCK_REDIRECTS] ?: false,
            allowlist = prefs[KEY_ALLOWLIST] ?: emptySet(),
            searchTemplate = prefs[KEY_SEARCH] ?: DEFAULT_SEARCH,
            cursorSpeed = prefs[KEY_CURSOR_SPEED] ?: DEFAULT_CURSOR_SPEED,
            directNav = prefs[KEY_DIRECT_NAV] ?: false,
            firstRunHintShown = prefs[KEY_HINT_SHOWN] ?: false,
            siteBrowsingSettings = SiteBrowsingSettingsJson.decode(
                prefs[KEY_SITE_BROWSING_SETTINGS]
            )
        )
    }

    suspend fun desktopModeNow(): Boolean = desktopMode.first()
    suspend fun blockNetworkNow(): Boolean = blockNetwork.first()
    suspend fun blockCosmeticNow(): Boolean = blockCosmetic.first()
    suspend fun blockPopupsNow(): Boolean = blockPopups.first()
    suspend fun blockRedirectsNow(): Boolean = blockRedirects.first()
    suspend fun allowlistNow(): Set<String> = allowlist.first()
    suspend fun searchTemplateNow(): String = searchTemplate.first()
    suspend fun cursorSpeedNow(): Int = cursorSpeed.first()
    suspend fun directNavNow(): Boolean = directNav.first()
    suspend fun firstRunHintShownNow(): Boolean = firstRunHintShown.first()
    suspend fun siteBrowsingSettingsNow(): Map<String, SiteBrowsingSettings> =
        siteBrowsingSettings.first()

    suspend fun getSiteBrowsingSettings(host: String): SiteBrowsingSettings? =
        SiteBrowsingPolicy.settingsForHost(siteBrowsingSettingsNow(), host)

    /** Stores an exact-host override. An all-default value removes the existing override. */
    suspend fun setSiteBrowsingSettings(host: String, settings: SiteBrowsingSettings) {
        val normalizedHost = requireNotNull(SiteBrowsingPolicy.normalizeHost(host)) {
            "Invalid site host"
        }
        ds.edit { prefs ->
            val current = SiteBrowsingSettingsJson.decode(
                prefs[KEY_SITE_BROWSING_SETTINGS]
            ).toMutableMap()
            if (settings.isDefault) current.remove(normalizedHost)
            else current[normalizedHost] = settings
            prefs[KEY_SITE_BROWSING_SETTINGS] = SiteBrowsingSettingsJson.encode(current)
        }
    }

    suspend fun removeSiteBrowsingSettings(host: String) {
        val normalizedHost = SiteBrowsingPolicy.normalizeHost(host) ?: return
        ds.edit { prefs ->
            val current = SiteBrowsingSettingsJson.decode(
                prefs[KEY_SITE_BROWSING_SETTINGS]
            ).toMutableMap()
            if (current.remove(normalizedHost) != null) {
                prefs[KEY_SITE_BROWSING_SETTINGS] = SiteBrowsingSettingsJson.encode(current)
            }
        }
    }

    suspend fun setFirstRunHintShown(shown: Boolean) = ds.edit { it[KEY_HINT_SHOWN] = shown }

    suspend fun setSearchTemplate(template: String) = ds.edit { it[KEY_SEARCH] = template }

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
        private val KEY_DESKTOP = booleanPreferencesKey("desktop_mode")
        private val KEY_BLOCK_NETWORK = booleanPreferencesKey("block_network")
        private val KEY_BLOCK_COSMETIC = booleanPreferencesKey("block_cosmetic")
        private val KEY_BLOCK_POPUPS = booleanPreferencesKey("block_popups")
        private val KEY_BLOCK_REDIRECTS = booleanPreferencesKey("block_redirects")
        private val KEY_ALLOWLIST = stringSetPreferencesKey("allowlist_hosts")
        private val KEY_SEARCH = stringPreferencesKey("search_template")
        private val KEY_CURSOR_SPEED = intPreferencesKey("cursor_speed")
        private val KEY_DIRECT_NAV = booleanPreferencesKey("direct_nav")
        private val KEY_HINT_SHOWN = booleanPreferencesKey("first_run_hint_shown")
        private val KEY_SITE_BROWSING_SETTINGS = stringPreferencesKey("site_browsing_settings_json")

        const val DEFAULT_SEARCH = "https://www.google.com/search?q=%s"
        /** Cursor speed slider value 1..5 (3 = normal). */
        const val DEFAULT_CURSOR_SPEED = 3
    }

    data class Snapshot(
        val desktopMode: Boolean,
        val blockNetwork: Boolean,
        val blockCosmetic: Boolean,
        val blockPopups: Boolean,
        val blockRedirects: Boolean,
        val allowlist: Set<String>,
        val searchTemplate: String,
        val cursorSpeed: Int,
        val directNav: Boolean,
        val firstRunHintShown: Boolean,
        val siteBrowsingSettings: Map<String, SiteBrowsingSettings>
    )
}
