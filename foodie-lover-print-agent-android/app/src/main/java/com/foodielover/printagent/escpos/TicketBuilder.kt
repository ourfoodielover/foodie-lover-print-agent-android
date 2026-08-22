package com.foodielover.printagent.escpos

import com.foodielover.printagent.network.KotPayload
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Direct Kotlin port of buildKot() / buildReceipt() in print-agent/index.js.
 * Field-for-field, command-for-command -- this file intentionally does NOT redesign the
 * ticket layout. The one unavoidable difference: index.js formats the timestamp via
 * JavaScript's `Date.toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', hour12: true })`,
 * whose exact ICU output (comma placement, am/pm casing) Java's date formatter cannot
 * reproduce byte-for-byte. The format below uses the same timezone, the same day/month/year
 * order, and the same 12-hour clock, which is what actually matters on a printed ticket.
 */
object TicketBuilder {

    private val KOLKATA: ZoneId = ZoneId.of("Asia/Kolkata")

    // en-IN date order is d/M/yyyy; matches the locale index.js requests.
    private val TIMESTAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d/M/yyyy, h:mm:ss a", Locale.ENGLISH).withZone(KOLKATA)

    private fun formatTimestamp(createdAtIso: String?): String {
        val instant = createdAtIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
        return TIMESTAMP_FORMAT.format(instant)
    }

    /** Mirrors index.js `row(left, right)` -- left-aligned label, right-aligned value. */
    private fun row(buf: TicketBuffer, left: String, right: String, charsPerLine: Int) {
        val space = maxOf(1, charsPerLine - left.length - right.length)
        buf.line(left + " ".repeat(space) + right)
    }

    private fun divider(buf: TicketBuffer, charsPerLine: Int, ch: Char = '-') {
        buf.line(ch.toString().repeat(charsPerLine))
    }

    /** Port of buildKot(payload). */
    fun buildKot(payload: KotPayload, charsPerLine: Int): ByteArray {
        val buf = TicketBuffer()
        buf.raw(EscPos.INIT).raw(EscPos.ALIGN_CENTER).raw(EscPos.DOUBLE_ON)
        buf.line("KITCHEN ORDER TICKET")
        buf.raw(EscPos.DOUBLE_OFF)
        buf.raw(EscPos.BOLD_ON)
        buf.line("#${payload.orderNumber?.toString() ?: payload.orderId}")
        buf.raw(EscPos.BOLD_OFF)
        buf.raw(EscPos.ALIGN_LEFT)
        divider(buf, charsPerLine, '=')

        val typeLabel = when (payload.type) {
            "delivery" -> "DELIVERY"
            "pickup" -> "PICKUP"
            else -> "DINE-IN"
        }
        buf.line("Type:  $typeLabel")
        if (!payload.tableId.isNullOrBlank()) buf.line("Table: ${payload.tableId}")
        if (!payload.customerName.isNullOrBlank()) buf.line("Guest: ${payload.customerName}")
        if (!payload.deliveryAddress.isNullOrBlank()) {
            val maxLen = maxOf(0, charsPerLine - 7)
            buf.line("Addr:  ${payload.deliveryAddress.take(maxLen)}")
        }
        buf.line("Time:  ${formatTimestamp(payload.createdAt)}")
        divider(buf, charsPerLine, '=')

        buf.raw(EscPos.BOLD_ON)
        for (item in payload.items) {
            val nameMax = maxOf(0, charsPerLine - 6)
            row(buf, item.name.take(nameMax), "x${item.qty}", charsPerLine)
        }
        buf.raw(EscPos.BOLD_OFF)
        divider(buf, charsPerLine, '=')

        if (!payload.notes.isNullOrBlank()) {
            buf.line("Note: ${payload.notes}")
            divider(buf, charsPerLine, '-')
        }

        buf.raw(EscPos.ALIGN_CENTER)
        buf.line("--- Send to kitchen ---")
        buf.raw(EscPos.feed(3))
        buf.raw(EscPos.CUT)
        return buf.toByteArray()
    }

    /** Port of buildReceipt(payload). */
    fun buildReceipt(payload: KotPayload, charsPerLine: Int, restaurantName: String): ByteArray {
        val buf = TicketBuffer()
        buf.raw(EscPos.INIT).raw(EscPos.ALIGN_CENTER).raw(EscPos.DOUBLE_ON)
        buf.line(restaurantName)
        buf.raw(EscPos.DOUBLE_OFF)
        buf.line("Order Receipt")
        buf.raw(EscPos.ALIGN_LEFT)
        divider(buf, charsPerLine, '=')
        buf.line("Order #${payload.orderNumber?.toString() ?: payload.orderId}")
        if (!payload.customerName.isNullOrBlank()) buf.line("Guest: ${payload.customerName}")
        divider(buf, charsPerLine, '-')
        for (item in payload.items) {
            val nameMax = maxOf(0, charsPerLine - 6)
            row(buf, item.name.take(nameMax), "x${item.qty}", charsPerLine)
        }
        divider(buf, charsPerLine, '=')
        buf.raw(EscPos.ALIGN_CENTER)
        buf.line("Thank you for visiting!")
        buf.raw(EscPos.feed(3))
        buf.raw(EscPos.CUT)
        return buf.toByteArray()
    }

    /** Minimal, self-contained ticket used by the Test Print button — doesn't touch the
     *  print_jobs API at all, mirrors index.js's `--test-print` payload. */
    fun buildTestTicket(charsPerLine: Int): ByteArray {
        val payload = KotPayload(
            orderId = "TEST",
            orderNumber = 0,
            type = "dine-in",
            tableId = "T01",
            customerName = "Test Order",
            deliveryAddress = null,
            items = listOf(
                KotPayload.Item("Chicken Burger", 2),
                KotPayload.Item("Fries (Large)", 1),
            ),
            notes = "Test print from Foodie Lover Android print agent.",
            createdAt = Instant.now().toString(),
        )
        return buildKot(payload, charsPerLine)
    }
}
