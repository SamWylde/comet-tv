package com.tdarby.comet.adblock

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodic background refresh of the downloaded filter list. */
class FilterUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (FilterListRepository(applicationContext).refresh()) Result.success() else Result.retry()
}
