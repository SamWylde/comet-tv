package com.tdarby.comet.permission

import java.net.URI

/** The only capture resources Comet exposes to sites. */
enum class SitePermissionResource(val webViewResource: String?) {
    CAMERA("android.webkit.resource.VIDEO_CAPTURE"),
    MICROPHONE("android.webkit.resource.AUDIO_CAPTURE"),
    PROTECTED_MEDIA("android.webkit.resource.PROTECTED_MEDIA_ID"),
    /** Geolocation uses GeolocationPermissions.Callback, not PermissionRequest.resources. */
    LOCATION(null);

    companion object {
        fun fromWebViewResource(value: String): SitePermissionResource? =
            entries.firstOrNull { it.webViewResource != null && it.webViewResource == value }
    }
}

enum class PermissionDecision {
    ALLOW_ONCE,
    ALLOW_SESSION,
    DENY
}

/** A decision key cannot accidentally grant a different capability or a different web origin. */
data class PermissionKey(
    val origin: String,
    val resource: SitePermissionResource
)

data class PermissionResolution(
    /** Resource strings safe to pass to PermissionRequest.grant(). */
    val grantedWebViewResources: Set<String>,
    /** Known resources that still need an allow-once/session/deny choice from the user. */
    val pendingDecisions: Set<PermissionKey>,
    /** Unknown, malformed, explicitly denied, or otherwise ungrantable resource strings. */
    val deniedWebViewResources: Set<String>
) {
    val hasPendingDecisions: Boolean get() = pendingDecisions.isNotEmpty()
}

interface SessionPermissionGrants {
    fun contains(key: PermissionKey): Boolean
    fun grant(key: PermissionKey)
    fun revoke(key: PermissionKey)
    fun clear()
}

/** Process-memory-only grants. They disappear when this store (normally the browser session) ends. */
class InMemorySessionPermissionGrants : SessionPermissionGrants {
    private val grants = mutableSetOf<PermissionKey>()

    @Synchronized
    override fun contains(key: PermissionKey): Boolean = key in grants

    @Synchronized
    override fun grant(key: PermissionKey) {
        grants += key
    }

    @Synchronized
    override fun revoke(key: PermissionKey) {
        grants -= key
    }

    @Synchronized
    override fun clear() {
        grants.clear()
    }
}

/**
 * Applies user choices to one WebView permission request. Missing choices are returned as pending;
 * unknown resource names and non-HTTP(S) origins are always denied.
 */
class PermissionPolicy(
    private val sessionGrants: SessionPermissionGrants = InMemorySessionPermissionGrants()
) {
    fun key(origin: String, resource: SitePermissionResource): PermissionKey? =
        normalizeWebOrigin(origin)?.let { PermissionKey(it, resource) }

    /** Applies an origin-scoped choice. Allow-once is never persisted. */
    fun applyDecision(key: PermissionKey, decision: PermissionDecision): Boolean {
        // Defend even if an integration constructs PermissionKey directly instead of using key().
        if (normalizeWebOrigin(key.origin) != key.origin) return false
        return when (decision) {
            PermissionDecision.ALLOW_ONCE -> true
            PermissionDecision.ALLOW_SESSION -> {
                sessionGrants.grant(key)
                true
            }
            PermissionDecision.DENY -> {
                sessionGrants.revoke(key)
                false
            }
        }
    }

    fun isGrantedForSession(key: PermissionKey): Boolean = sessionGrants.contains(key)

    fun resolve(
        origin: String,
        requestedWebViewResources: Collection<String>,
        decisions: Map<PermissionKey, PermissionDecision> = emptyMap()
    ): PermissionResolution {
        val normalizedOrigin = normalizeWebOrigin(origin)
        val granted = linkedSetOf<String>()
        val pending = linkedSetOf<PermissionKey>()
        val denied = linkedSetOf<String>()

        requestedWebViewResources.distinct().forEach { rawResource ->
            val resource = SitePermissionResource.fromWebViewResource(rawResource)
            if (resource == null || normalizedOrigin == null) {
                denied += rawResource
                return@forEach
            }

            val key = PermissionKey(normalizedOrigin, resource)
            val decision = decisions[key]
            when {
                decision != null && applyDecision(key, decision) -> granted += rawResource
                decision != null -> denied += rawResource
                sessionGrants.contains(key) -> granted += rawResource
                else -> pending += key
            }
        }

        return PermissionResolution(granted, pending, denied)
    }

    fun clearSessionGrants() = sessionGrants.clear()

    companion object {
        /** Canonical scheme/host/port identity; paths, credentials, queries, and fragments are ignored. */
        fun normalizeWebOrigin(origin: String): String? = runCatching {
            val uri = URI(origin.trim())
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (scheme !in setOf("http", "https") || host.isNullOrBlank() || uri.userInfo != null) {
                return null
            }
            val defaultPort = (scheme == "http" && uri.port == 80) ||
                (scheme == "https" && uri.port == 443)
            "$scheme://$host${if (uri.port >= 0 && !defaultPort) ":${uri.port}" else ""}"
        }.getOrNull()
    }
}
