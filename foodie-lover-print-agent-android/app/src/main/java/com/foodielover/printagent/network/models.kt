package com.foodielover.printagent.network

import org.json.JSONArray
import org.json.JSONObject

/** Mirrors buildKotPayload() in app/api/orders/[id]/route.ts -- the JSONB stored in
 *  print_jobs.payload. Field names match the JSON keys exactly (camelCase, as written by
 *  the Next.js route, not the snake_case used by the print_jobs table columns themselves). */
data class KotPayload(
    val orderId: String,
    val orderNumber: Int?,
    val type: String?,
    val tableId: String?,
    val customerName: String?,
    val deliveryAddress: String?,
    val items: List<Item>,
    val notes: String?,
    val createdAt: String?,
) {
    data class Item(val name: String, val qty: Int)

    companion object {
        fun fromJson(obj: JSONObject): KotPayload {
            val items = mutableListOf<Item>()
            val arr: JSONArray? = obj.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    items.add(Item(name = item.optString("name", ""), qty = item.optInt("qty", 1)))
                }
            }
            return KotPayload(
                orderId = obj.optString("orderId", ""),
                orderNumber = if (obj.isNull("orderNumber")) null else obj.optInt("orderNumber"),
                type = obj.optString("type", null),
                tableId = if (obj.isNull("tableId")) null else obj.optString("tableId"),
                customerName = if (obj.isNull("customerName")) null else obj.optString("customerName"),
                deliveryAddress = if (obj.isNull("deliveryAddress")) null else obj.optString("deliveryAddress"),
                items = items,
                notes = if (obj.isNull("notes")) null else obj.optString("notes"),
                createdAt = if (obj.isNull("createdAt")) null else obj.optString("createdAt"),
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
