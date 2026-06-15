package com.tdarby.comet

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tdarby.comet.adblock.FilterListRepository
import com.tdarby.comet.adblock.FilterUpdateWorker
import java.util.concurrent.TimeUnit

class CometApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Load bundled + cached blocklist synchronously so blocking is active from the first page.
        FilterListRepository(this).loadInitial()
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
