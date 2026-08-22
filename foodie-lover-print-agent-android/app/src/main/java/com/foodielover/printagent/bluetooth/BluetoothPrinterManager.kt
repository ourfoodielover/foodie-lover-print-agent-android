package com.foodielover.printagent.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic SPP/RFCOMM connection to the paired CN811 -- confirmed working in the
 * Phase 0 physical test (Serial Bluetooth Terminal app, standard OS-level pairing, raw bytes
 * printed correctly). One socket at a time; the caller (PrintService) is responsible for
 * making sure only one print is ever in flight, this class does not queue writes itself.
 */
class BluetoothPrinterManager(private val context: Context) {

    companion object {
        /** Standard Serial Port Profile UUID. This is what Android's createRfcommSocketToServiceRecord
         *  uses to ask the remote device for its SPP channel -- the same profile the Phase 0 test
         *  proved the CN811 exposes. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    @Volatile private var socket: BluetoothSocket? = null

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    val isConnected: Boolean get() = socket?.isConnected == true

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /** Caller must have already confirmed BLUETOOTH_CONNECT before invoking this --
     *  PrintService checks permissions before starting the loop and before Test Print. */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceAddress: String): Result<Unit> = withContext(Dispatchers.IO) {
        close()
        val bt = adapter
            ?: return@withContext Result.failure(IOException("Bluetooth is not available on this device"))
        if (!bt.isEnabled) {
            return@withContext Result.failure(IOException("Bluetooth is turned off"))
        }
        val device: BluetoothDevice = try {
            bt.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            return@withContext Result.failure(IOException("Invalid Bluetooth address: $deviceAddress"))
        }
        try {
            // Android's own docs warn an in-progress discovery scan can slow down or break
            // an RFCOMM connect attempt -- cancel it defensively before connecting.
            runCatching { bt.cancelDiscovery() }
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            Result.success(Unit)
        } catch (e: IOException) {
            runCatching { socket?.close() }
            socket = null
            Result.failure(e)
        } catch (e: SecurityException) {
            Result.failure(IOException("Missing Bluetooth permission: ${e.message}"))
        }
    }

    suspend fun write(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext Result.failure(IOException("Printer not connected"))
        try {
            s.outputStream.write(bytes)
            s.outputStream.flush()
            Result.success(Unit)
        } catch (e: IOException) {
            // A write failure means the link is already dead -- drop it so the next attempt
            // reconnects from scratch instead of retrying a socket that will never recover.
            close()
            Result.failure(e)
        }
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    @SuppressLint("MissingPermission")
    fun bondedDeviceName(deviceAddress: String): String? =
        adapter?.bondedDevices?.firstOrNull { it.address == deviceAddress }?.name

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList().orEmpty()
}
