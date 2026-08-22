package com.foodielover.printagent.config

/**
 * One-time setup values — the Android equivalent of print-agent/.env on the Windows agent.
 * Defaults mirror print-agent/.env.example exactly so an installer who doesn't change
 * anything ends up pointed at the same "default" values the web app already assumes.
 *
 * IMPORTANT: every print_jobs row inserted by app/api/orders/[id]/route.ts today omits
 * printer_id, so it always defaults to 'default' at the DB level (see migration_010.sql).
 * stationId therefore MUST be "default" unless a future server change starts routing jobs
 * by station — do not change this default casually, it will silently see zero jobs.
 */
data class AppConfig(
    val baseUrl: String = "",
    val printAgentKey: String = "",
    val restaurantId: String = "rest_default",
    val stationId: String = "default",
    val charsPerLine: Int = 42,
    val restaurantName: String = "Foodie Lover",
    val pollIntervalMs: Long = 4000L,
    val maxAttempts: Int = 5,
    val printerDeviceAddress: String? = null,
    val printerDeviceName: String? = null,
    /** True once the manager has tapped "Start Print Service" and hasn't tapped Stop since.
     *  This is what BootReceiver checks -- a reboot only restarts the service if it was
     *  deliberately left running, never just because credentials happen to be configured. */
    val serviceEnabled: Boolean = false,
) {
    /** Minimum needed to start the poll loop at all. Printer selection is checked separately
     *  so the UI can distinguish "not configured" from "configured but printer not picked". */
    fun hasServerConfig(): Boolean =
        baseUrl.isNotBlank() && printAgentKey.isNotBlank()

    fun hasPrinterConfig(): Boolean =
        !printerDeviceAddress.isNullOrBlank()

    fun isFullyConfigured(): Boolean = hasServerConfig() && hasPrinterConfig()

    /** baseUrl with any trailing slash stripped, so path concatenation never double-slashes. */
    fun normalizedBaseUrl(): String = baseUrl.trimEnd('/')
}
