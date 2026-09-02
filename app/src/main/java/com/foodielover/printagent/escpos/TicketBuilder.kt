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

    /**
     * CUSTOMER_RECEIPT -- a financial document that may include payment info ("Payment: UPI",
     * "Status: PAID"). Replaces the old placeholder buildReceipt() (item names/quantities only,
     * no prices) which was never reachable from the current web app -- the only UI action that
     * could request job_type="receipt" (Reprint) always hardcodes "kot" today (see
     * app/waiter/page.tsx's handleReprint()). See the final report for the full audit trail.
     *
     * REQUIRED fields (throws TicketFormatException, never falls back to KOT, if missing):
     * payload.total, at least one item, and unitPrice+lineTotal on every item -- see section 18
     * of the spec this was built against: a financial document must never show quantity only.
     * Everything else (subtotal, coupon, discount, payment method/status, phone, address,
     * amountToCollect) is printed only when the server actually supplies it.
     */
    fun buildReceipt(payload: KotPayload, charsPerLine: Int, restaurantName: String, isReprint: Boolean = false): ByteArray =
        renderFinancialDocument(DocumentType.CUSTOMER_RECEIPT, payload, charsPerLine, restaurantName, isReprint).toByteArray()

    /** DEBUG-only dry-run preview -- runs the exact same validation and content decisions as
     *  buildReceipt() (same shared function, same required-field checks, same exceptions on
     *  failure) but returns plain text instead of ESC/POS bytes. Never writes to Bluetooth,
     *  never called by the production print path. See debug/JobDiagnostics.kt. */
    fun previewReceipt(payload: KotPayload, charsPerLine: Int, restaurantName: String, isReprint: Boolean = false): String =
        renderFinancialDocument(DocumentType.CUSTOMER_RECEIPT, payload, charsPerLine, restaurantName, isReprint).previewText()

    /**
     * CUSTOMER_BILL -- a financial statement of what the customer owes, not necessarily paid
     * yet (no payment-method line; only an optional "Payment Status:" line, printed verbatim).
     * New formatter -- no prior version of this existed. Same required-field contract as
     * buildReceipt() above.
     */
    fun buildBill(payload: KotPayload, charsPerLine: Int, restaurantName: String, isReprint: Boolean = false): ByteArray =
        renderFinancialDocument(DocumentType.CUSTOMER_BILL, payload, charsPerLine, restaurantName, isReprint).toByteArray()

    /** DEBUG-only dry-run preview -- see previewReceipt() above for what this does and why. */
    fun previewBill(payload: KotPayload, charsPerLine: Int, restaurantName: String, isReprint: Boolean = false): String =
        renderFinancialDocument(DocumentType.CUSTOMER_BILL, payload, charsPerLine, restaurantName, isReprint).previewText()

    // ── Shared engine for buildBill()/buildReceipt() ────────────────────────────────────────
    // Deliberately NOT shared with buildKot() -- KOT is regression-protected and untouched
    // above. This is the one implementation both financial formatters delegate to, per "do not
    // create a second ESC/POS implementation": bill and receipt differ only in header text and
    // which payment-related lines are shown, not in the underlying ESC/POS commands used.
    private fun renderFinancialDocument(
        kind: DocumentType,
        payload: KotPayload,
        charsPerLine: Int,
        restaurantName: String,
        isReprint: Boolean,
    ): TicketBuffer {
        // ── Required-field validation -- fail safely, never guess, never fall back to KOT ──
        val total = payload.total
            ?: throw TicketFormatException("${kind.name} payload for order ${payload.orderId} is missing required field: total")
        if (payload.items.isEmpty()) {
            throw TicketFormatException("${kind.name} payload for order ${payload.orderId} has no items")
        }
        payload.items.forEachIndexed { index, item ->
            if (item.unitPrice == null || item.lineTotal == null) {
                throw TicketFormatException(
                    "${kind.name} payload for order ${payload.orderId} is missing unitPrice/lineTotal " +
                        "on item ${index + 1} (\"${item.name}\") -- a financial document cannot show quantity only",
                )
            }
        }

        val orderType = payload.type ?: "dine-in"
        val isDineIn = orderType == "dine-in"
        val isDelivery = orderType == "delivery"
        val docWord = if (kind == DocumentType.CUSTOMER_BILL) "BILL" else "RECEIPT"
        val headerLabel = when {
            isDineIn -> docWord
            orderType == "pickup" -> "PICKUP $docWord"
            isDelivery -> "DELIVERY $docWord"
            else -> docWord
        }
        val customerName = payload.customerName?.takeIf { it.isNotBlank() } ?: "Guest"
        val paidNormalized = payload.paymentStatus?.trim()?.uppercase()

        val buf = TicketBuffer()
        buf.raw(EscPos.INIT).raw(EscPos.ALIGN_CENTER).raw(EscPos.DOUBLE_ON)
        buf.line(restaurantName)
        buf.raw(EscPos.DOUBLE_OFF)
        divider(buf, charsPerLine, '=')
        buf.raw(EscPos.BOLD_ON)
        buf.line(headerLabel)
        buf.raw(EscPos.BOLD_OFF)
        if (isReprint) buf.line("*** REPRINT ***")
        buf.raw(EscPos.ALIGN_LEFT)

        // ── Header detail lines ──────────────────────────────────────────────────────────
        if (isDineIn) {
            if (!payload.tableId.isNullOrBlank()) buf.line("Table ${payload.tableId}")
            buf.line("Customer: $customerName")
        } else {
            buf.line("Customer: $customerName")
            if (!payload.phone.isNullOrBlank()) buf.line("Phone: ${payload.phone}")
            if (isDelivery && !payload.deliveryAddress.isNullOrBlank()) {
                buf.line("Delivery Address:")
                for (addrLine in wrapText(payload.deliveryAddress, charsPerLine)) buf.line(addrLine)
            }
        }

        val docNumber = payload.documentNumber ?: payload.orderNumber?.toString() ?: payload.orderId
        if (isDineIn) {
            buf.line("${if (kind == DocumentType.CUSTOMER_BILL) "Bill" else "Receipt"} #$docNumber")
            buf.line("Date: ${formatDateOnly(payload.createdAt)}")
            buf.line("Time: ${formatTimeOnly(payload.createdAt)}")
        } else {
            buf.line("Order #$docNumber")
            buf.line("Date/Time: ${formatTimestamp(payload.createdAt)}")
        }
        divider(buf, charsPerLine, '-')

        // ── Items -- name (wrapped) + optional variant line + qty/price row ────────────────
        for (item in payload.items) {
            for (nameLine in wrapText(item.name, charsPerLine)) buf.line(nameLine)
            if (!item.variant.isNullOrBlank()) buf.line(item.variant)
            row(buf, "${item.qty} x Rs. ${formatMoney(item.unitPrice!!)}", "Rs. ${formatMoney(item.lineTotal!!)}", charsPerLine)
        }
        divider(buf, charsPerLine, '-')

        // ── Totals -- printed only when the server supplied the value; never computed here ──
        payload.subtotal?.let { row(buf, "Subtotal", "Rs. ${formatMoney(it)}", charsPerLine) }
        if (!payload.couponCode.isNullOrBlank()) buf.line("Coupon: ${payload.couponCode}")
        payload.discountAmount?.let {
            row(buf, payload.discountLabel?.takeIf { l -> l.isNotBlank() } ?: "Discount", "-Rs. ${formatMoney(it)}", charsPerLine)
        }
        if (kind == DocumentType.CUSTOMER_BILL) {
            divider(buf, charsPerLine, '-')
            buf.raw(EscPos.BOLD_ON)
            row(buf, "TOTAL DUE", "Rs. ${formatMoney(total)}", charsPerLine)
            buf.raw(EscPos.BOLD_OFF)
            divider(buf, charsPerLine, '-')
            if (!payload.paymentStatus.isNullOrBlank()) buf.line("Payment Status: ${payload.paymentStatus}")
        } else {
            divider(buf, charsPerLine, '-')
            buf.raw(EscPos.BOLD_ON)
            row(buf, "TOTAL", "Rs. ${formatMoney(total)}", charsPerLine)
            buf.raw(EscPos.BOLD_OFF)
            if (!payload.paymentMethod.isNullOrBlank()) buf.line("Payment: ${payload.paymentMethod}")
            if (!payload.paymentStatus.isNullOrBlank()) buf.line("Status: ${payload.paymentStatus}")

            // ── COD emphasis block -- delivery only, only when the server sent an amount,
            // and never when the server has explicitly marked the order PAID. ──────────────
            val amountToCollect = payload.amountToCollect
            if (isDelivery && amountToCollect != null && amountToCollect > 0.0 && paidNormalized != "PAID") {
                buf.raw(EscPos.ALIGN_CENTER)
                divider(buf, charsPerLine, '*')
                buf.raw(EscPos.BOLD_ON)
                buf.line("AMOUNT TO COLLECT")
                buf.line("Rs. ${formatMoney(amountToCollect)}")
                buf.raw(EscPos.BOLD_OFF)
                divider(buf, charsPerLine, '*')
                buf.raw(EscPos.ALIGN_LEFT)
            }
        }

        buf.raw(EscPos.ALIGN_CENTER)
        buf.line("Thank You")
        divider(buf, charsPerLine, '=')
        buf.raw(EscPos.feed(3))
        buf.raw(EscPos.CUT)
        return buf
    }

    // ── Formatting helpers used only by the financial-document formatters above ────────────
    private fun formatMoney(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded == Math.floor(rounded)) {
            String.format(Locale.ENGLISH, "%.0f", rounded)
        } else {
            String.format(Locale.ENGLISH, "%.2f", rounded)
        }
    }

    /** Greedy word-wrap so a long item name or delivery address never overwrites the
     *  qty/price columns on the following line -- unlike buildKot()'s single-line truncate,
     *  which is left exactly as-is for the kitchen ticket. */
    private fun wrapText(text: String, maxLen: Int): List<String> {
        if (text.isBlank()) return emptyList()
        val width = maxOf(1, maxLen)
        val words = text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidateLen = if (current.isEmpty()) word.length else current.length + 1 + word.length
            if (candidateLen > width && current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            // A single word longer than the whole line width is hard-cut so it can't corrupt
            // the layout; this only ever triggers for a token wider than the printer itself.
            if (word.length > width) {
                var remaining = word
                while (remaining.length > width) {
                    if (current.isNotEmpty()) { lines.add(current.toString()); current = StringBuilder() }
                    lines.add(remaining.take(width))
                    remaining = remaining.drop(width)
                }
                current.append(remaining)
            } else {
                current.append(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    private fun formatDateOnly(createdAtIso: String?): String {
        val instant = createdAtIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
        return DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH).withZone(KOLKATA).format(instant)
    }

    private fun formatTimeOnly(createdAtIso: String?): String {
        val instant = createdAtIso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()
        return DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH).withZone(KOLKATA).format(instant)
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
