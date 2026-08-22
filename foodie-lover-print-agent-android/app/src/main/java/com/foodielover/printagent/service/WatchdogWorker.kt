package com.foodielover.printagent.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.foodielover.printagent.config.SecureConfig
import java.util.concurrent.TimeUnit

/**
 * WATCHDOG ONLY. This worker never calls PrintJobsApi and never touches Bluetooth directly --
 * its entire job is "if the manager left the service enabled, make sure it's actually alive".
 *
 * It does this by unconditionally (re-)starting PrintService, which is safe to call whether
 * or not the service is already running: PrintService.startLoopIfNeeded() is a no-op if its
 * poll loop is already active, so this can never spin up a second, concurrent poller -- it
 * either does nothing (service already fine) or recovers a service the OS killed.
 *
 * 15 minutes is WorkManager's minimum periodic interval; it exists purely as a backstop for
 * process death (OEM battery managers, OOM kill) between the foreground service's own
 * START_STICKY restart and the next time the manager happens to glance at the app.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val secureConfig = SecureConfig(applicationContext)
        val config = secureConfig.load()
        if (config.serviceEnabled && config.isFullyConfigured()) {
            val intent = Intent(applicationContext, PrintService::class.java).apply {
                action = PrintService.ACTION_START
            }
            ContextCompat.startForegroundService(applicationContext, intent)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "print_service_watchdog"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
