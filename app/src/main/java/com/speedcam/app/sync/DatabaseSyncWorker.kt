package com.speedcam.app.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.speedcam.service.ProfileStore
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker executing periodic or on-demand camera database synchronization.
 *
 * Constraints for periodic runs:
 *  - Unmetered network (Wi-Fi)
 *  - Battery not low
 *  - Storage not low
 *  - Interval: 7 days with exponential retry backoff
 */
class DatabaseSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.i(TAG, "DatabaseSyncWorker triggered (attempt: $runAttemptCount)")
        val profiles = ProfileStore(applicationContext)
        val isPeriodic = inputData.getBoolean(KEY_IS_PERIODIC, false)

        if (isPeriodic && !profiles.autoUpdateEnabled) {
            Log.i(TAG, "Auto-update is disabled; skipping periodic work.")
            return Result.success()
        }

        setProgress(workDataOf(KEY_PROGRESS_STATUS to "CHECKING"))

        val syncEngine = DatabaseSyncEngine(applicationContext)
        val url = profiles.updateUrl.ifBlank { DatabaseSyncEngine.DEFAULT_MANIFEST_URL }

        val syncResult = syncEngine.sync(url)
        profiles.lastUpdateCheckAt =
            java.time.Instant
                .now()
                .toString()
        profiles.lastUpdateStatus = "${syncResult.outcome}: ${syncResult.message}"

        return when (syncResult.outcome) {
            SyncOutcome.UPDATED, SyncOutcome.ALREADY_UP_TO_DATE -> {
                Log.i(TAG, "DatabaseSyncWorker finished: ${syncResult.message}")
                Result.success(workDataOf(KEY_RESULT_MESSAGE to syncResult.message))
            }
            SyncOutcome.FAILED -> {
                Log.w(TAG, "DatabaseSyncWorker failed: ${syncResult.message}")
                if (runAttemptCount < MAX_RETRIES && isPeriodic) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf(KEY_RESULT_MESSAGE to syncResult.message))
                }
            }
        }
    }

    companion object {
        private const val TAG = "DatabaseSyncWorker"
        private const val MAX_RETRIES = 3

        const val WORK_NAME_PERIODIC = "speedcam_periodic_database_sync"
        const val WORK_NAME_IMMEDIATE = "speedcam_immediate_database_sync"
        const val KEY_IS_PERIODIC = "is_periodic"
        const val KEY_PROGRESS_STATUS = "progress_status"
        const val KEY_RESULT_MESSAGE = "result_message"

        /** Schedules weekly background updates via WorkManager. */
        fun schedulePeriodic(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<DatabaseSyncWorker>(7, TimeUnit.DAYS, 1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .setInputData(workDataOf(KEY_IS_PERIODIC to true))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Cancels periodic updates if disabled by the user. */
        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        }

        /** Enqueues an immediate one-time sync task on any connected network. */
        fun triggerImmediate(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<DatabaseSyncWorker>()
                    .setConstraints(constraints)
                    .setInputData(workDataOf(KEY_IS_PERIODIC to false))
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
