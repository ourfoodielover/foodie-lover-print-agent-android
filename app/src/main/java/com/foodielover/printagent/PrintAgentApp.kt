package com.foodielover.printagent

import android.app.Application
import com.foodielover.printagent.service.WatchdogWorker

class PrintAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Cheap and idempotent -- WatchdogWorker itself no-ops unless the manager has left
        // the service enabled, so scheduling it unconditionally here is safe.
        WatchdogWorker.schedule(this)
    }
}
