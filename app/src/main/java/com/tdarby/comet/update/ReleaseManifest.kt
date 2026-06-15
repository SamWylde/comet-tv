package com.tdarby.comet.update

/** Update descriptor for one flavor, parsed from the hosted JSON manifest. */
data class ReleaseManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String?,
    val required: Boolean
)
