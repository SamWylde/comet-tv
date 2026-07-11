package com.tdarby.comet

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tdarby.comet.adblock.FilterListRepository
import com.tdarby.comet.adblock.FilterUpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class CometApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val filters = FilterListRepository(this)
        // Keep the tiny bundled baseline available for the first request, but never make the launch
        // wait while a ~3 MB downloaded list is parsed on slower TV hardware.
        filters.loadBundled()
        appScope.launch { filters.loadCached() }
        scheduleFilterUpdates()
    }

    private fun scheduleFilterUpdates() {
        val request = PeriodicWorkRequestBuilder<FilterUpdateWorker>(3, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "comet_filter_update",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
