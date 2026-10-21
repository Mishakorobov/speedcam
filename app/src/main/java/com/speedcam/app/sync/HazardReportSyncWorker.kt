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
import com.speedcam.app.SpeedCamApp
import com.speedcam.core.data.ReportSyncStatus
import com.speedcam.service.ProfileStore
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Drains the offline hazard report outbox when network connectivity is available.
 *
 * Implements AREAS_FOR_DEVELOPMENT.md [M-30]:
 *  - Queries pending reports from Room
 *  - Delivers reports via [HazardReportSender]
 *  - Updates state to SYNCED or increments retry counter with backoff
 */
class HazardReportSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.i(TAG, "HazardReportSyncWorker running (attempt: $runAttemptCount)")
        val store = SpeedCamApp.cameraStore(applicationContext)
        val profiles = ProfileStore(applicationContext)
        val pending = runCatching { store.hazardReportDao.pendingReports() }.getOrDefault(emptyList())

        if (pending.isEmpty()) {
            Log.i(TAG, "Outbox is empty; nothing to sync.")
            profiles.lastHazardSyncAt = Instant.now().toString()
            profiles.lastHazardSyncStatus = "Up to date (0 pending)"
            return Result.success(workDataOf(KEY_SYNCED_COUNT to 0))
        }

        val url = profiles.hazardReportUrl.ifBlank { HttpHazardReportSender.DEFAULT_HAZARD_URL }
        val sender = senderOverride ?: HttpHazardReportSender(url)

        var syncedCount = 0
        var failedCount = 0

        for (report in pending) {
            val sendResult = sender.send(report)
            val now = System.currentTimeMillis()
            if (sendResult.isSuccess) {
                store.hazardReportDao.markSynced(report.id, sendResult.getOrNull())
                syncedCount++
            } else {
                failedCount++
                val nextStatus =
                    if (report.attempts + 1 >= MAX_RETRIES) {
                        ReportSyncStatus.FAILED.ordinal
                    } else {
                        ReportSyncStatus.PENDING.ordinal
                    }
                store.hazardReportDao.recordAttempt(report.id, now, nextStatus)
                Log.w(TAG, "Failed to upload report #${report.id}: ${sendResult.exceptionOrNull()?.message}")
            }
        }

        profiles.lastHazardSyncAt = Instant.now().toString()
        profiles.lastHazardSyncStatus = "Synced $syncedCount report(s), $failedCount failed"

        return if (failedCount > 0 && runAttemptCount < MAX_RETRIES) {
            Result.retry()
        } else {
            Result.success(workDataOf(KEY_SYNCED_COUNT to syncedCount, KEY_FAILED_COUNT to failedCount))
        }
    }

    companion object {
        private const val TAG = "HazardReportSyncWorker"
        private const val MAX_RETRIES = 3

        const val WORK_NAME_PERIODIC = "speedcam_periodic_hazard_sync"
        const val WORK_NAME_IMMEDIATE = "speedcam_immediate_hazard_sync"
        const val KEY_SYNCED_COUNT = "synced_count"
        const val KEY_FAILED_COUNT = "failed_count"

        /** Injectable sender for unit testing without network calls. */
        @Volatile
        var senderOverride: HazardReportSender? = null

        /** Triggers an immediate one-off attempt to sync the pending outbox. */
        fun triggerImmediate(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<HazardReportSyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        /** Schedules periodic outbox flushing when connected. */
        fun schedulePeriodic(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<HazardReportSyncWorker>(6, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
