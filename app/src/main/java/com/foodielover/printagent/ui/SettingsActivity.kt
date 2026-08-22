package com.foodielover.printagent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.foodielover.printagent.config.AppConfig
import com.foodielover.printagent.config.SecureConfig
import com.foodielover.printagent.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var secureConfig: SecureConfig
    private var pendingDeviceAddress: String? = null
    private var pendingDeviceName: String? = null

    private val devicePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        pendingDeviceAddress = data.getStringExtra(DevicePickerActivity.EXTRA_DEVICE_ADDRESS)
        pendingDeviceName = data.getStringExtra(DevicePickerActivity.EXTRA_DEVICE_NAME)
        binding.textSelectedDevice.text = pendingDeviceName ?: pendingDeviceAddress ?: "No printer selected"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) launchDevicePicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        secureConfig = SecureConfig(this)

        val config = secureConfig.load()
        binding.inputBaseUrl.setText(config.baseUrl)
        binding.inputAgentKey.setText(config.printAgentKey)
        binding.inputRestaurantId.setText(config.restaurantId)
        binding.inputStationId.setText(config.stationId)
        binding.inputCharsPerLine.setText(config.charsPerLine.toString())
        pendingDeviceAddress = config.printerDeviceAddress
        pendingDeviceName = config.printerDeviceName
        binding.textSelectedDevice.text = pendingDeviceName ?: "No printer selected"

        binding.buttonPickDevice.setOnClickListener { onPickDeviceClicked() }
        binding.buttonSaveSettings.setOnClickListener { onSaveClicked() }
    }

    private fun onPickDeviceClicked() {
        if (needsBluetoothPermission()) {
            permissionLauncher.launch(bluetoothPermissions())
            return
        }
        launchDevicePicker()
    }

    private fun launchDevicePicker() {
        devicePickerLauncher.launch(Intent(this, DevicePickerActivity::class.java))
    }

    private fun needsBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else emptyArray()

    private fun onSaveClicked() {
        val baseUrl = binding.inputBaseUrl.text?.toString()?.trim().orEmpty()
        val agentKey = binding.inputAgentKey.text?.toString()?.trim().orEmpty()
        val restaurantId = binding.inputRestaurantId.text?.toString()?.trim()
            .takeUnless { it.isNullOrBlank() } ?: "rest_default"
        val stationId = binding.inputStationId.text?.toString()?.trim()
            .takeUnless { it.isNullOrBlank() } ?: "default"
        val charsPerLine = binding.inputCharsPerLine.text?.toString()?.trim()?.toIntOrNull() ?: 42

        if (baseUrl.isBlank()) {
            Toast.makeText(this, "APP_BASE_URL is required", Toast.LENGTH_LONG).show()
            return
        }
        if (agentKey.isBlank()) {
            Toast.makeText(this, "PRINT_AGENT_KEY is required", Toast.LENGTH_LONG).show()
            return
        }

        val existing = secureConfig.load()
        val updated = existing.copy(
            baseUrl = baseUrl,
            printAgentKey = agentKey,
            restaurantId = restaurantId,
            stationId = stationId,
            charsPerLine = charsPerLine,
            printerDeviceAddress = pendingDeviceAddress ?: existing.printerDeviceAddress,
            printerDeviceName = pendingDeviceName ?: existing.printerDeviceName,
        )
        secureConfig.save(updated)

        if (com.foodielover.printagent.service.ServiceStatus.state.value.serviceRunning) {
            // Service is already running -- push it the new config immediately instead of
            // leaving it polling with whatever it loaded at last start.
            val reload = Intent(this, com.foodielover.printagent.service.PrintService::class.java).apply {
                action = com.foodielover.printagent.service.PrintService.ACTION_RELOAD_CONFIG
            }
            androidx.core.content.ContextCompat.startForegroundService(this, reload)
        }

        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
