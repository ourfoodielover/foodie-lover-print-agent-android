package com.foodielover.printagent.network

import org.json.JSONArray
import org.json.JSONObject

/** Mirrors buildKotPayload() in app/api/orders/[id]/route.ts -- the JSONB stored in
 *  print_jobs.payload. Field names match the JSON keys exactly (camelCase, as written by
 *  the Next.js route, not the snake_case used by the print_jobs table columns themselves).
 *
 *  The class name is historical (it started as "the KOT payload") and is kept as-is to avoid
 *  an unnecessary rename across PrintService/TicketBuilder -- it now also carries the payload
 *  for CUSTOMER_BILL / CUSTOMER_RECEIPT jobs. Every field below the original KOT set is a NEW,
 *  purely additive, nullable field: a legacy KOT payload (which never contains any of them)
 *  continues to parse exactly as before, and none of them are read by buildKot(), which is
 *  unchanged. See TicketBuilder.buildBill()/buildReceipt() and DocumentType.kt for how they're
 *  consumed, and the final report for the exact server contract these correspond to.
 *
 *  IMPORTANT: none of these are computed on-device. If the server doesn't send a field, the
 *  corresponding line is simply omitted from the printed document -- Android never derives a
 *  financial value (subtotal/discount/total/etc.) itself. */
data class KotPayload(
    val orderId: String,
    val orderNumber: Int?,
    /** "dine-in" | "pickup" | "delivery". Legacy KOT payloads (buildKotPayload() in
     *  app/api/orders/[id]/route.ts) send this under the key "type". The newer CUSTOMER_BILL /
     *  CUSTOMER_RECEIPT payload builder sends the same concept under the key "orderType"
     *  instead. Both are accepted -- see fromJson() below -- with "orderType" taking priority
     *  when a payload happens to contain both (it never should in practice, but the financial
     *  key is treated as canonical since it's the newer/authoritative one). This field itself
     *  is unchanged; only which JSON key(s) populate it changed. */
    val type: String?,
    val tableId: String?,
    val customerName: String?,
    val deliveryAddress: String?,
    val items: List<Item>,
    val notes: String?,
    val createdAt: String?,
    // ── NEW: financial-document fields (CUSTOMER_BILL / CUSTOMER_RECEIPT only) ──────────────
    /** Explicit document type, e.g. "KITCHEN_TICKET" | "CUSTOMER_BILL" | "CUSTOMER_RECEIPT".
     *  Not sent by the server today -- see DocumentType.kt for full resolution rules and the
     *  final report for why this has to live inside the JSONB payload rather than job_type. */
    val documentType: String? = null,
    /** Bill/receipt/order number to print, e.g. "Bill #100". Falls back to orderNumber/orderId
     *  in the formatter if absent -- never required on its own. */
    val documentNumber: String? = null,
    /** Customer phone -- pickup/delivery receipts only; dine-in bills must not print this
     *  unless the server explicitly sends it for a specific existing requirement. Legacy KOT
     *  payloads send this under the key "phone"; the CUSTOMER_BILL / CUSTOMER_RECEIPT payload
     *  builder sends it under "customerPhone" instead. Both are accepted -- see fromJson()
     *  below -- with "customerPhone" taking priority as the canonical/newer key. Never derived
     *  from customerName; parsed only from these two explicit keys. */
    val phone: String? = null,
    val subtotal: Double? = null,
    val couponCode: String? = null,
    val discountLabel: String? = null,
    val discountAmount: Double? = null,
    /** Authoritative grand total / amount due. REQUIRED for CUSTOMER_BILL and CUSTOMER_RECEIPT
     *  -- buildBill()/buildReceipt() fail the job safely (never fall back to KOT) if absent. */
    val total: Double? = null,
    /** e.g. "PAID" | "PENDING". Printed verbatim, never inferred from document type or any
     *  other field -- if absent, no payment-status line is printed at all. */
    val paymentStatus: String? = null,
    /** e.g. "UPI" | "CASH" | "CARD" | "CASH ON DELIVERY". */
    val paymentMethod: String? = null,
    /** Delivery COD only -- amount to physically collect. Printed prominently when present and
     *  greater than zero; never printed alongside an explicit paymentStatus == "PAID". */
    val amountToCollect: Double? = null,
) {
    data class Item(
        val name: String,
        val qty: Int,
        // ── NEW: optional per-line financial fields (see KotPayload's own NEW-fields note) ──
        /** e.g. "Half" / "Full" / "Large" -- printed on its own line under the item name when
         *  present. The current server bakes variant text into `name` itself (see
         *  buildKotPayload()); this field is forward-compatible in case that changes. */
        val variant: String? = null,
        val unitPrice: Double? = null,
        val lineTotal: Double? = null,
    )

    companion object {
        private fun optDoubleOrNull(obj: JSONObject, key: String): Double? =
            if (obj.has(key) && !obj.isNull(key)) obj.optDouble(key).takeUnless { it.isNaN() } else null

        private fun optStringOrNull(obj: JSONObject, key: String): String? =
            if (obj.has(key) && !obj.isNull(key)) obj.optString(key).takeIf { it.isNotBlank() } else null

        fun fromJson(obj: JSONObject): KotPayload {
            val items = mutableListOf<Item>()
            val arr: JSONArray? = obj.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    items.add(
                        Item(
                            name = item.optString("name", ""),
                            qty = item.optInt("qty", 1),
                            variant = optStringOrNull(item, "variant"),
                            unitPrice = optDoubleOrNull(item, "unitPrice"),
                            lineTotal = optDoubleOrNull(item, "lineTotal"),
                        ),
                    )
                }
            }
            return KotPayload(
                orderId = obj.optString("orderId", ""),
                orderNumber = if (obj.isNull("orderNumber")) null else obj.optInt("orderNumber"),
                // Canonical financial key first ("orderType"), legacy KOT key as fallback
                // ("type") -- see the field's own doc comment above for why both exist.
                type = optStringOrNull(obj, "orderType") ?: optStringOrNull(obj, "type"),
                tableId = if (obj.isNull("tableId")) null else obj.optString("tableId"),
                customerName = if (obj.isNull("customerName")) null else obj.optString("customerName"),
                deliveryAddress = if (obj.isNull("deliveryAddress")) null else obj.optString("deliveryAddress"),
                items = items,
                notes = if (obj.isNull("notes")) null else obj.optString("notes"),
                createdAt = if (obj.isNull("createdAt")) null else obj.optString("createdAt"),
                documentType = optStringOrNull(obj, "documentType"),
                documentNumber = optStringOrNull(obj, "documentNumber"),
                // Canonical financial key first ("customerPhone"), legacy KOT key as fallback
                // ("phone") -- see the field's own doc comment above. Never parsed from
                // customerName.
                phone = optStringOrNull(obj, "customerPhone") ?: optStringOrNull(obj, "phone"),
                subtotal = optDoubleOrNull(obj, "subtotal"),
                couponCode = optStringOrNull(obj, "couponCode"),
                discountLabel = optStringOrNull(obj, "discountLabel"),
                discountAmount = optDoubleOrNull(obj, "discountAmount"),
                total = optDoubleOrNull(obj, "total"),
                paymentStatus = optStringOrNull(obj, "paymentStatus"),
                paymentMethod = optStringOrNull(obj, "paymentMethod"),
                amountToCollect = optDoubleOrNull(obj, "amountToCollect"),
            )
        }
    }
}

/** Mirrors the row shape returned by GET /api/print-jobs:
 *  `id, order_id, job_type, status, printer_id, payload, attempts, is_reprint, created_at`. */
data class PrintJob(
    val id: String,
    val orderId: String,
    val jobType: String,
    val status: String,
    val printerId: String?,
    val payload: KotPayload,
    val attempts: Int,
    val isReprint: Boolean,
    val createdAt: String?,
) {
    companion object {
        fun fromJson(obj: JSONObject): PrintJob = PrintJob(
            id = obj.getString("id"),
            orderId = obj.optString("order_id", ""),
            jobType = obj.optString("job_type", "kot"),
            status = obj.optString("status", "queued"),
            printerId = if (obj.isNull("printer_id")) null else obj.optString("printer_id"),
            payload = KotPayload.fromJson(obj.getJSONObject("payload")),
            attempts = obj.optInt("attempts", 0),
            isReprint = obj.optBoolean("is_reprint", false),
            createdAt = if (obj.isNull("created_at")) null else obj.optString("created_at"),
        )
    }
}
