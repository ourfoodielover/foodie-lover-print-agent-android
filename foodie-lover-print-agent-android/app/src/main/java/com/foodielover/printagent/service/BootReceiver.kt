package com.foodielover.printagent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.foodielover.printagent.config.SecureConfig

/**
 * Restarts the print service after a tablet reboot -- but ONLY if the manager had
 * deliberately left the service running (SecureConfig.isServiceEnabled()) before the reboot,
 * and only if setup is complete. A reboot never starts the service on its own just because
 * credentials happen to be saved.
 *
 * Note for the manager: Android will only deliver BOOT_COMPLETED to an app that has been
 * launched at least once since install (a freshly-installed, never-opened app is in
 * "stopped state" and boot broadcasts are withheld from it). Since first-time setup requires
 * opening the app anyway, this is a non-issue after the one-time setup flow in Section H.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val secureConfig = SecureConfig(context)
        val config = secureConfig.load()
        if (!config.serviceEnabled || !config.isFullyConfigured()) return

        val serviceIntent = Intent(context, PrintService::class.java).apply {
            action = PrintService.ACTION_START
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
