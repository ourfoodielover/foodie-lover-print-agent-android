package com.foodielover.printagent.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnState { UNKNOWN, CONNECTED, DISCONNECTED }

data class LastPrintInfo(
    val orderLabel: String,
    val atMillis: Long,
    val success: Boolean,
)

data class StatusSnapshot(
    val serviceRunning: Boolean = false,
    val serverState: ConnState = ConnState.UNKNOWN,
    val printerState: ConnState = ConnState.UNKNOWN,
    val printerName: String? = null,
    val lastPrint: LastPrintInfo? = null,
)

/**
 * In-process shared state between PrintService (the only writer) and MainActivity (the
 * reader/observer). Both run in the same process (no android:process override anywhere in
 * the manifest), so a plain singleton + StateFlow is sufficient -- no Messenger/AIDL needed,
 * and no polling from the UI side either, it just collects the flow.
 */
object ServiceStatus {
    private val _state = MutableStateFlow(StatusSnapshot())
    val state: StateFlow<StatusSnapshot> = _state.asStateFlow()

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.ENGLISH)

    fun setServiceRunning(running: Boolean) {
        _state.value = _state.value.copy(serviceRunning = running)
        if (!running) {
            _state.value = _state.value.copy(serverState = ConnState.UNKNOWN, printerState = ConnState.UNKNOWN)
        }
    }

    fun setServerState(connected: Boolean) {
        _state.value = _state.value.copy(serverState = if (connected) ConnState.CONNECTED else ConnState.DISCONNECTED)
    }

    fun setPrinterState(connected: Boolean, name: String?) {
        _state.value = _state.value.copy(
            printerState = if (connected) ConnState.CONNECTED else ConnState.DISCONNECTED,
            printerName = name ?: _state.value.printerName,
        )
    }

    fun setLastPrint(orderLabel: String, success: Boolean) {
        _state.value = _state.value.copy(
            lastPrint = LastPrintInfo(orderLabel = orderLabel, atMillis = System.currentTimeMillis(), success = success),
        )
    }

    fun formatLastPrintTime(millis: Long): String = timeFormat.format(Date(millis))
}
