package com.foodielover.printagent.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.foodielover.printagent.R
import com.foodielover.printagent.bluetooth.BluetoothPrinterManager
import com.foodielover.printagent.databinding.ActivityDevicePickerBinding

/**
 * Lists already-paired (bonded) Bluetooth Classic devices and lets the manager tap the CN811.
 * Pairing itself happens in Android's own Bluetooth settings, outside this app -- confirmed
 * as the right flow in the Phase 0 test. This screen only ever picks from bonded devices,
 * it never scans for new/unpaired ones.
 */
class DevicePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDevicePickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevicePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.device_picker_title)

        val manager = BluetoothPrinterManager(this)
        val devices = manager.bondedDevices()

        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "No paired Bluetooth devices found. Pair the CN811 in Android Bluetooth settings first.",
                Toast.LENGTH_LONG,
            ).show()
        }

        binding.recyclerDevices.layoutManager = LinearLayoutManager(this)
        binding.recyclerDevices.adapter = DeviceAdapter(devices) { device -> onDeviceChosen(device) }
    }

    @SuppressLint("MissingPermission") // permission already confirmed by SettingsActivity before launch
    private fun onDeviceChosen(device: BluetoothDevice) {
        val result = Intent().apply {
            putExtra(EXTRA_DEVICE_ADDRESS, device.address)
            putExtra(EXTRA_DEVICE_NAME, device.name ?: device.address)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}

private class DeviceAdapter(
    private val devices: List<BluetoothDevice>,
    private val onClick: (BluetoothDevice) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.textDeviceName)
        val address: TextView = view.findViewById(R.id.textDeviceAddress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bonded_device, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.name.text = device.name ?: "(unnamed device)"
        holder.address.text = device.address
        holder.itemView.setOnClickListener { onClick(device) }
    }

    override fun getItemCount(): Int = devices.size
}
