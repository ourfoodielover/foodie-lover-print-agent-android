package com.foodielover.printagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.foodielover.printagent.BuildConfig
import com.foodielover.printagent.config.SecureConfig
import com.foodielover.printagent.databinding.ActivityMainBinding
import com.foodielover.printagent.debug.JobDiagnostics
import com.foodielover.printagent.escpos.DocumentType
import com.foodielover.printagent.service.ConnState
import com.foodielover.printagent.service.PrintService
import com.foodielover.printagent.service.StatusSnapshot
import com.foodielover.printagent.service.ServiceStatus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var secureConfig: SecureConfig

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Result observed via hasRequiredPermissions() the next time an action is attempted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        secureConfig = SecureConfig(this)

        binding.buttonToggleService.setOnClickListener { onToggleServiceClicked() }
        binding.buttonTestPrint.setOnClickListener { onTestPrintClicked() }
        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // DEBUG-only diagnostics -- see debug/JobDiagnostics.kt. Never shown in a release
        // build; BuildConfig.DEBUG is generated per build type once buildFeatures.buildConfig
        // is enabled in app/build.gradle.kts (required as of AGP 8.x -- it's opt-in there now).
        if (BuildConfig.DEBUG) {
            binding.dividerDebug.visibility = android.view.View.VISIBLE
            binding.buttonDebugDryRun.visibility = android.view.View.VISIBLE
            binding.buttonDebugDryRun.setOnClickListener { onDebugDryRunClicked() }
        }

        observeStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStaticFields()
    }

    private fun refreshStaticFields() {
        val config = secureConfig.load()
        binding.textPrinterName.text = config.printerDeviceName ?: "Not selected"
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceStatus.state.collect { render(it) }
            }
        }
    }

    private fun render(status: StatusSnapshot) {
        setDot(binding.dotServer, status.serverState)
        binding.textServerStatus.text = labelFor(status.serverState, "Connected", "Disconnected")

        setDot(binding.dotPrinter, status.printerState)
        binding.textPrinterStatus.text = labelFor(status.printerState, "CN811 Connected", "CN811 Disconnected")

        binding.textServiceStatus.text = if (status.serviceRunning) "Running" else "Stopped"
        setDotBool(binding.dotService, status.serviceRunning)

        binding.buttonToggleService.text = if (status.serviceRunning) "Stop Print Service" else "Start Print Service"

        val last = status.lastPrint
        if (last == null) {
            binding.textLastPrintOrder.text = "—"
            binding.textLastPrintDetail.text = ""
        } else {
            binding.textLastPrintOrder.text = last.orderLabel
            val time = ServiceStatus.formatLastPrintTime(last.atMillis)
            val result = if (last.success) "SUCCESS" else "FAILED"
            binding.textLastPrintDetail.text = "$time · $result"
        }

        if (status.printerName != null) {
            binding.textPrinterName.text = status.printerName
        }
    }

    private fun labelFor(state: ConnState, connectedText: String, disconnectedText: String): String = when (state) {
        ConnState.CONNECTED -> connectedText
        ConnState.DISCONNECTED -> disconnectedText
        ConnState.UNKNOWN -> "Checking…"
    }

    private fun setDot(view: android.widget.ImageView, state: ConnState) {
        val color = when (state) {
            ConnState.CONNECTED -> R_GOOD
            ConnState.DISCONNECTED -> R_DANGER
            ConnState.UNKNOWN -> R_NEUTRAL
        }
        view.setColorFilter(color)
    }

    private fun setDotBool(view: android.widget.ImageView, on: Boolean) {
        view.setColorFilter(if (on) R_GOOD else R_NEUTRAL)
    }

    // ── Actions ───────────────────────────────────────────────────────────────────────────

    private fun onToggleServiceClicked() {
        val running = ServiceStatus.state.value.serviceRunning
        if (running) {
            secureConfig.setServiceEnabled(false)
            val intent = Intent(this, PrintService::class.java).apply { action = PrintService.ACTION_STOP }
            startService(intent)
            return
        }

        val config = secureConfig.load()
        if (!config.isFullyConfigured()) {
            Toast.makeText(this, "Finish Settings first (server + printer)", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            Toast.makeText(this, "Grant the requested permissions, then tap Start again", Toast.LENGTH_LONG).show()
            return
        }

        maybeRequestBatteryOptimizationExemption()

        secureConfig.setServiceEnabled(true)
        val intent = Intent(this, PrintService::class.java).apply { action = PrintService.ACTION_START }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onTestPrintClicked() {
        val config = secureConfig.load()
        if (!config.hasPrinterConfig()) {
            Toast.makeText(this, "Choose the CN811 in Settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            Toast.makeText(this, "Grant the requested permissions, then tap Test Print again", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, PrintService::class.java).apply { action = PrintService.ACTION_TEST_PRINT }
        ContextCompat.startForegroundService(this, intent)
    }

    /** DEBUG-only. Fetches the current queued/failed jobs (a plain GET -- see
     *  JobDiagnostics's own doc comment for the full safety contract) and shows, for each one:
     *  job id, job_type, payload.documentType, printer_id, the resolved DocumentType, which
     *  formatter was selected, success/failure, any validation error, byte count, and a
     *  plain-text preview. Never prints, never touches Bluetooth, never PATCHes job status. */
    private fun onDebugDryRunClicked() {
        val config = secureConfig.load()
        if (!config.hasServerConfig()) {
            Toast.makeText(this, "Finish Settings first (server URL + agent key)", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val result = runCatching { JobDiagnostics.dryRun(config) }
            val message = result.fold(
                onSuccess = { diagnoses ->
                    if (diagnoses.isEmpty()) {
                        "No queued/failed jobs returned by GET /api/print-jobs right now."
                    } else {
                        diagnoses.joinToString("\n\n${"─".repeat(32)}\n\n") { d ->
                            buildString {
                                append("job id: ${d.jobId}\n")
                                append("job_type: ${d.jobType}\n")
                                append("payload.documentType: ${d.documentType ?: "(none)"}\n")
                                append("printer_id: ${d.printerId ?: "(none)"}\n")
                                append("resolved: ${d.resolvedType}\n")
                                append("formatter: ${d.formatterName}\n")
                                if (d.success) {
                                    append("result: SUCCESS")
                                    if (d.byteCount != null) append(" (${d.byteCount} bytes)")
                                    append("\n")
                                    if (d.previewText != null) append("\n${d.previewText}")
                                } else {
                                    append("result: FAILED\n")
                                    append("error: ${d.errorMessage}")
                                }
                            }
                        }
                    }
                },
                onFailure = { e -> "Could not fetch jobs: ${e.message ?: e.toString()}" },
            )
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Dry-run job preview (no print)")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val needed = requiredPermissions()
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRequiredPermissions() {
        permissionLauncher.launch(requiredPermissions().toTypedArray())
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
    }

    private fun maybeRequestBatteryOptimizationExemption() {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            runCatching { startActivity(intent) }
        }
    }

    companion object {
        private val R_GOOD = Color.parseColor("#2E7D4F")
        private val R_DANGER = Color.parseColor("#A3312B")
        private val R_NEUTRAL = Color.parseColor("#7A8179")
    }
}
