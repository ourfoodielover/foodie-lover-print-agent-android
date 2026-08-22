package com.foodielover.printagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.foodielover.printagent.bluetooth.BluetoothPrinterManager
import com.foodielover.printagent.config.AppConfig
import com.foodielover.printagent.config.SecureConfig
import com.foodielover.printagent.escpos.TicketBuilder
import com.foodielover.printagent.network.PrintJob
import com.foodielover.printagent.network.PrintJobsApi
import com.foodielover.printagent.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay

/**
 * The single owner of the poll/print loop -- everything else (WatchdogWorker, BootReceiver,
 * MainActivity's Start button) only ever starts/stops THIS service. Nothing else calls
 * PrintJobsApi.fetchJobs(). That is what "only one active polling loop" means in practice.
 *
 * Lifecycle mirrors print-agent/index.js's pollOnce()/processJob() exactly:
 *   GET queued+failed jobs -> for each (sequential): PATCH printing -> render+print -> PATCH printed|failed.
 *
 * KNOWN LIMITATION (inherited from the existing server, not introduced here): if this
 * process dies between the PATCH "printing" call and the follow-up PATCH "printed"/"failed"
 * call, that job is stuck at status='printing' forever -- GET /api/print-jobs only ever
 * returns 'queued'/'failed' rows, so nothing retries it automatically. Recovery today is the
 * waiter portal's existing "Reprint KOT" button. This is not fixed here per instructions to
 * preserve current server behavior -- see the Phase 0 analysis doc, section G.
 */
class PrintService : Service() {

    companion object {
        const val ACTION_START = "com.foodielover.printagent.action.START"
        const val ACTION_STOP = "com.foodielover.printagent.action.STOP"
        const val ACTION_TEST_PRINT = "com.foodielover.printagent.action.TEST_PRINT"
        const val ACTION_RELOAD_CONFIG = "com.foodielover.printagent.action.RELOAD_CONFIG"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "print_service_status"

        private const val BACKOFF_MIN_MS = 2_000L
        private const val BACKOFF_MAX_MS = 30_000L
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var loopJob: Job? = null
    private val printMutex = Mutex() // guarantees one ESC/POS write in flight at a time,
    // shared by the automatic loop AND the Test Print button so they can never interleave.

    private lateinit var secureConfig: SecureConfig
    private lateinit var bluetoothManager: BluetoothPrinterManager
    private var config: AppConfig = AppConfig()

    private var nextReconnectAtMs = 0L
    private var currentBackoffMs = BACKOFF_MIN_MS

    override fun onCreate() {
        super.onCreate()
        secureConfig = SecureConfig(this)
        bluetoothManager = BluetoothPrinterManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLoop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                ServiceStatus.setServiceRunning(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST_PRINT -> {
                // Test Print works even before the manager has pressed "Start Print Service" --
                // in that case this promotes to foreground just long enough to print, then
                // tears itself back down so a one-off test doesn't leave a phantom "running"
                // service with no poll loop behind it.
                val loopWasRunning = loopJob?.isActive == true
                startAsForeground()
                scope.launch {
                    runTestPrint()
                    if (!loopWasRunning) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        ServiceStatus.setServiceRunning(false)
                        stopSelf()
                    }
                }
                return START_STICKY
            }
            ACTION_RELOAD_CONFIG -> {
                // Settings changed while the service was running -- restart the loop with
                // freshly loaded config rather than silently keep polling with stale values.
                // This is the ONLY path that ever cancels+relaunches loopJob, so it still
                // never results in two loops running at once.
                startAsForeground()
                stopLoop()
                startLoopIfNeeded()
                return START_STICKY
            }
            else -> {
                startAsForeground()
                startLoopIfNeeded()
                return START_STICKY
            }
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        ServiceStatus.setServiceRunning(true)
    }

    private fun startLoopIfNeeded() {
        if (loopJob?.isActive == true) return // already running -- refuse to start a second loop
        config = secureConfig.load()
        loopJob = scope.launch { pollLoop() }
    }

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
        bluetoothManager.close()
    }

    override fun onDestroy() {
        stopLoop()
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Main loop ─────────────────────────────────────────────────────────────────────────

    private suspend fun pollLoop() {
        val api = PrintJobsApi(config)

        while (scope.isActive) {
            // Reconnect the printer on a backoff schedule so a powered-off CN811 doesn't get
            // hammered every single poll tick; job polling continues regardless of printer
            // state so the queue keeps draining the moment the printer comes back.
            if (config.hasPrinterConfig() && !bluetoothManager.isConnected) {
                maybeReconnectPrinter()
            }

            val jobs = runCatching { api.fetchJobs() }
            if (jobs.isFailure) {
                ServiceStatus.setServerState(false)
            } else {
                ServiceStatus.setServerState(true)
                val list = jobs.getOrDefault(emptyList())
                for (jobItem in list) {
                    if (!scope.isActive) break
                    if (jobItem.attempts >= config.maxAttempts) continue // matches pollOnce()'s skip
                    processJob(api, jobItem)
                }
            }

            updateNotification()
            delay(config.pollIntervalMs)
        }
    }

    /** Direct port of processJob() in print-agent/index.js. */
    private suspend fun processJob(api: PrintJobsApi, job: PrintJob) = printMutex.withLock {
        val orderLabel = "Order #${job.payload.orderNumber ?: job.orderId}"
        try {
            api.updateJobStatus(job.id, "printing")

            val ticket = if (job.jobType == "receipt") {
                TicketBuilder.buildReceipt(job.payload, config.charsPerLine, config.restaurantName)
            } else {
                TicketBuilder.buildKot(job.payload, config.charsPerLine)
            }

            printTicketOrThrow(ticket)

            api.updateJobStatus(job.id, "printed")
            ServiceStatus.setLastPrint(orderLabel, success = true)
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            ServiceStatus.setLastPrint(orderLabel, success = false)
            runCatching { api.updateJobStatus(job.id, "failed", message) }
            // Matches index.js: a failed PATCH-of-the-failure is logged and swallowed, never
            // rethrown -- one bad job must never take down the whole poll loop.
        } finally {
            ServiceStatus.setPrinterState(bluetoothManager.isConnected, config.printerDeviceName)
        }
    }

    /** Ensures a connection (subject to the same backoff as the idle-loop reconnect) and
     *  writes the ticket, throwing if either step fails -- this IS the "printBuffer()" step
     *  from index.js, just over Bluetooth instead of USB/TCP. */
    private suspend fun printTicketOrThrow(ticket: ByteArray) {
        if (!bluetoothManager.isConnected) {
            maybeReconnectPrinter(force = true)
        }
        if (!bluetoothManager.isConnected) {
            throw java.io.IOException("Printer not connected (Bluetooth)")
        }
        bluetoothManager.write(ticket).getOrThrow()
    }

    private suspend fun maybeReconnectPrinter(force: Boolean = false) {
        val address = config.printerDeviceAddress ?: return
        val now = System.currentTimeMillis()
        if (!force && now < nextReconnectAtMs) return

        val result = bluetoothManager.connect(address)
        if (result.isSuccess) {
            currentBackoffMs = BACKOFF_MIN_MS
            nextReconnectAtMs = 0L
        } else {
            nextReconnectAtMs = now + currentBackoffMs
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        }
        ServiceStatus.setPrinterState(bluetoothManager.isConnected, config.printerDeviceName)
    }

    private suspend fun runTestPrint() = printMutex.withLock {
        config = secureConfig.load() // pick up latest settings in case they changed since loop start
        try {
            if (!bluetoothManager.isConnected) {
                maybeReconnectPrinter(force = true)
            }
            if (!bluetoothManager.isConnected) {
                throw java.io.IOException("Printer not connected (Bluetooth)")
            }
            val ticket = TicketBuilder.buildTestTicket(config.charsPerLine)
            bluetoothManager.write(ticket).getOrThrow()
            ServiceStatus.setLastPrint("Test Print", success = true)
        } catch (e: Exception) {
            ServiceStatus.setLastPrint("Test Print", success = false)
        } finally {
            ServiceStatus.setPrinterState(bluetoothManager.isConnected, config.printerDeviceName)
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.foodielover.printagent.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW, // low: no sound/heads-up, this is a status light, not an alert
        ).apply {
            description = getString(com.foodielover.printagent.R.string.notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Foodie Lover Print Service")
            .setContentText(statusText)
            .setSmallIcon(com.foodielover.printagent.R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val server = if (ServiceStatus.state.value.serverState == ConnState.CONNECTED) "Connected" else "Disconnected"
        val printer = if (bluetoothManager.isConnected) "Connected" else "Disconnected"
        val text = "Server: $server · Printer: $printer"
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
