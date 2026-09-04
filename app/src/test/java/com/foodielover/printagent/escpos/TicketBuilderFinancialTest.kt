package com.foodielover.printagent.escpos

import com.foodielover.printagent.network.KotPayload
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rendering-side coverage for the payload-contract fix (network/models.kt KotPayload.fromJson()):
 * once payload.type / payload.phone are populated correctly (from either the canonical financial
 * JSON keys or the legacy KOT keys), TicketBuilder's existing, UNCHANGED rendering logic must
 * produce the right document. This file changes no formatter code -- it only exercises
 * previewBill()/previewReceipt()/buildKot() (all pre-existing, public, non-Bluetooth-touching
 * entry points) against payloads built the same two ways a real payload can arrive.
 */
class TicketBuilderFinancialTest {

    private val charsPerLine = 42
    private val restaurantName = "Foodie Lover"

    private fun item(name: String, qty: Int, unitPrice: Double, lineTotal: Double) =
        KotPayload.Item(name = name, qty = qty, unitPrice = unitPrice, lineTotal = lineTotal)

    private fun payload(
        type: String?,
        phone: String? = null,
        customerName: String? = "Guest",
        deliveryAddress: String? = null,
        amountToCollect: Double? = null,
        paymentStatus: String? = null,
        total: Double = 140.0,
    ) = KotPayload(
        orderId = "ORD_TEST",
        orderNumber = 74,
        type = type,
        tableId = null,
        customerName = customerName,
        deliveryAddress = deliveryAddress,
        items = listOf(item("Chicken Fry Piece Biryani (Half)", 1, 140.0, 140.0)),
        notes = null,
        createdAt = "2026-09-02T07:56:56.750Z",
        phone = phone,
        subtotal = 140.0,
        total = total,
        paymentStatus = paymentStatus,
        amountToCollect = amountToCollect,
    )

    // ── Matrix 1: CUSTOMER_BILL, delivery, phone present ────────────────────────────────────

    @Test
    fun `matrix 1 -- CUSTOMER_BILL delivery renders DELIVERY BILL with phone, address, and AMOUNT TO COLLECT`() {
        val p = payload(
            type = "delivery", phone = "9052797254", deliveryAddress = "Sarda.pg",
            amountToCollect = 140.0, paymentStatus = "PENDING",
        )
        val preview = TicketBuilder.previewBill(p, charsPerLine, restaurantName)

        assertTrue("expected header 'DELIVERY BILL'", preview.contains("DELIVERY BILL"))
        assertTrue("expected 'Phone: 9052797254'", preview.contains("Phone: 9052797254"))
        assertTrue("expected delivery address line", preview.contains("Sarda.pg"))
        assertTrue("expected TOTAL DUE row", preview.contains("TOTAL DUE"))
        assertTrue(
            "AMOUNT TO COLLECT is now shared by CUSTOMER_BILL and CUSTOMER_RECEIPT (COD delivery, not PAID)",
            preview.contains("AMOUNT TO COLLECT"),
        )
    }

    @Test
    fun `matrix 1b -- CUSTOMER_BILL delivery marked PAID does not show AMOUNT TO COLLECT`() {
        val p = payload(
            type = "delivery", phone = "9052797254", deliveryAddress = "Sarda.pg",
            amountToCollect = 140.0, paymentStatus = "PAID",
        )
        val preview = TicketBuilder.previewBill(p, charsPerLine, restaurantName)
        assertFalse("a PAID order must never show AMOUNT TO COLLECT, Bill or Receipt", preview.contains("AMOUNT TO COLLECT"))
    }

    @Test
    fun `matrix 1c -- CUSTOMER_BILL dine-in never shows AMOUNT TO COLLECT even with amountToCollect set`() {
        val p = payload(type = "dine-in", amountToCollect = 140.0, paymentStatus = "PENDING")
        val preview = TicketBuilder.previewBill(p, charsPerLine, restaurantName)
        assertFalse("COD block is delivery-only regardless of document kind", preview.contains("AMOUNT TO COLLECT"))
    }

    // ── Matrix 2: CUSTOMER_RECEIPT, delivery, phone present ─────────────────────────────────

    @Test
    fun `matrix 2 -- CUSTOMER_RECEIPT delivery renders DELIVERY RECEIPT with phone, and AMOUNT TO COLLECT`() {
        val p = payload(
            type = "delivery", phone = "9052797254", deliveryAddress = "Sarda.pg",
            amountToCollect = 140.0, paymentStatus = "PENDING",
        )
        val preview = TicketBuilder.previewReceipt(p, charsPerLine, restaurantName)

        assertTrue("expected header 'DELIVERY RECEIPT'", preview.contains("DELIVERY RECEIPT"))
        assertTrue("expected 'Phone: 9052797254'", preview.contains("Phone: 9052797254"))
        assertTrue("expected AMOUNT TO COLLECT block (receipt path only, see report)", preview.contains("AMOUNT TO COLLECT"))
    }

    // ── Matrix 3/4: pickup ───────────────────────────────────────────────────────────────

    @Test
    fun `matrix 3 -- CUSTOMER_BILL pickup renders PICKUP BILL with phone`() {
        val p = payload(type = "pickup", phone = "9052797254")
        val preview = TicketBuilder.previewBill(p, charsPerLine, restaurantName)
        assertTrue(preview.contains("PICKUP BILL"))
        assertTrue(preview.contains("Phone: 9052797254"))
    }

    @Test
    fun `matrix 4 -- CUSTOMER_RECEIPT pickup renders PICKUP RECEIPT with phone`() {
        val p = payload(type = "pickup", phone = "9052797254")
        val preview = TicketBuilder.previewReceipt(p, charsPerLine, restaurantName)
        assertTrue(preview.contains("PICKUP RECEIPT"))
        assertTrue(preview.contains("Phone: 9052797254"))
    }

    // ── Matrix 5: dine-in unchanged -- no phone line even if payload.phone is present ──────

    @Test
    fun `matrix 5 -- dine-in Bill never prints a Phone line, even if phone is present`() {
        val p = payload(type = "dine-in", phone = "9052797254", customerName = "Table 4 Guest")
        val preview = TicketBuilder.previewBill(p, charsPerLine, restaurantName)
        assertFalse("dine-in must not print Phone per existing design", preview.contains("Phone:"))
        assertTrue(preview.contains("BILL"))
        assertFalse("dine-in header must not say DELIVERY/PICKUP", preview.contains("DELIVERY BILL") || preview.contains("PICKUP BILL"))
    }

    @Test
    fun `matrix 5b -- null type (absent orderType and type) defaults to dine-in, same as before this fix`() {
        val p = payload(type = null)
        val preview = TicketBuilder.previewBill(p, charsPerLine, restaurantName)
        assertFalse(preview.contains("Phone:"))
        assertFalse(preview.contains("DELIVERY") || preview.contains("PICKUP"))
    }

    // ── Matrix 6: legacy KOT -- buildKot() output/routing unaffected ───────────────────────

    @Test
    fun `matrix 6 -- buildKot() still resolves 'delivery' type via legacy JSON key 'type', unaffected by this fix`() {
        val legacyJson = JSONObject().apply {
            put("orderId", "ORD_KOT")
            put("orderNumber", 12)
            put("type", "delivery") // legacy key -- no orderType in a real KOT payload
            put("customerName", "Guest")
            put("deliveryAddress", "12 MG Road")
            put("items", JSONArray().put(JSONObject().apply { put("name", "Veg Puff"); put("qty", 2) }))
        }
        val payload = KotPayload.fromJson(legacyJson)
        val bytes = TicketBuilder.buildKot(payload, charsPerLine)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue("legacy KOT type routing must be unaffected", text.contains("DELIVERY"))
        assertTrue(text.contains("KITCHEN ORDER TICKET"))
    }

    // ── Matrix 7: both aliases present -- canonical wins (rendering-level confirmation) ────

    @Test
    fun `matrix 7 -- when both orderType and type keys are present, the financial (orderType) key drives rendering`() {
        val json = JSONObject().apply {
            put("orderId", "ORD_TEST")
            put("orderType", "delivery")
            put("type", "dine-in") // must be ignored in favor of orderType
            put("customerPhone", "1111111111")
            put("phone", "2222222222") // must be ignored in favor of customerPhone
            put("total", 140)
            put(
                "items",
                JSONArray().put(
                    JSONObject().apply {
                        put("name", "Item"); put("qty", 1); put("unitPrice", 140); put("lineTotal", 140)
                    },
                ),
            )
        }
        val payload = KotPayload.fromJson(json)
        val preview = TicketBuilder.previewBill(payload, charsPerLine, restaurantName)
        assertTrue("orderType='delivery' must win over type='dine-in'", preview.contains("DELIVERY BILL"))
        assertTrue("customerPhone must win over phone", preview.contains("Phone: 1111111111"))
        assertFalse(preview.contains("Phone: 2222222222"))
    }

    // ── Matrix 8: missing customerPhone -- no "Phone: null", no blank Phone line ────────────

    @Test
    fun `matrix 8 -- missing phone produces no Phone line at all, never 'Phone: null'`() {
        val p = payload(type = "delivery", phone = null, deliveryAddress = "12 MG Road")
        val preview = TicketBuilder.previewBill(p, charsPerLine, restaurantName)
        assertFalse(preview.contains("Phone:"))
        assertFalse(preview.contains("Phone: null"))
    }

    // ── Real example verification ───────────────────────────────────────────────────────────

    @Test
    fun `real example -- parses and renders the exact reported payload`() {
        // Matches the real payload shape reported for the delivery bill in question.
        val json = JSONObject().apply {
            put("documentType", "CUSTOMER_BILL")
            put("orderType", "delivery")
            put("orderId", "ORD_REAL_EXAMPLE")
            put("customerName", "KJayalaxmi 9052797254 Sarda.pg")
            put("customerPhone", "9052797254")
            put("deliveryAddress", "Sarda.pg")
            put("subtotal", 140)
            put("total", 140)
            put("paymentStatus", "PENDING")
            put("amountToCollect", 140)
            put(
                "items",
                JSONArray().put(
                    JSONObject().apply {
                        put("name", "Chicken Fry Piece Biryani (Half)")
                        put("qty", 1)
                        put("unitPrice", 140)
                        put("lineTotal", 140)
                    },
                ),
            )
        }
        val payload = KotPayload.fromJson(json)

        // -- parser assertions --
        assertTrue("payload.type must resolve to 'delivery'", payload.type == "delivery")
        assertTrue("payload.phone must resolve to '9052797254'", payload.phone == "9052797254")

        // -- formatter assertions (previewBill -- matches DocumentType.CUSTOMER_BILL routing) --
        val preview = TicketBuilder.previewBill(payload, charsPerLine, restaurantName)
        println("=== real-example preview (CUSTOMER_BILL) ===\n$preview\n=== end preview ===")

        assertTrue("expected 'DELIVERY BILL' header, not generic 'BILL'", preview.contains("DELIVERY BILL"))
        assertFalse(preview.lines().any { it.trim() == "BILL" })
        assertTrue("expected 'Phone: 9052797254'", preview.contains("Phone: 9052797254"))
        assertTrue("expected delivery address 'Sarda.pg'", preview.contains("Sarda.pg"))
        assertTrue("expected TOTAL DUE row", preview.contains("TOTAL DUE"))
        assertTrue(
            "expected AMOUNT TO COLLECT block for a PENDING COD delivery bill",
            preview.contains("AMOUNT TO COLLECT"),
        )
        assertTrue("expected the collected amount on its own line", preview.contains("Rs. 140"))
    }

    @Test
    fun `real example -- second real payload (Mahesh, Bill #40) matches the reported expected output`() {
        // Matches the second real-world example: a delivery order that previously printed as a
        // generic dine-in "BILL" with no phone/address/COD line.
        val json = JSONObject().apply {
            put("documentType", "CUSTOMER_BILL")
            put("orderType", "delivery")
            put("orderId", "ORD_MAHESH_40")
            put("orderNumber", 40)
            put("customerName", "Mahesh")
            put("customerPhone", "9876500000") // placeholder -- real number not provided
            put("deliveryAddress", "Placeholder Address") // placeholder -- real address not provided
            put("subtotal", 220)
            put("discountAmount", 0)
            put("total", 220)
            put("paymentStatus", "PENDING")
            put("amountToCollect", 220)
            put(
                "items",
                JSONArray().put(
                    JSONObject().apply {
                        put("name", "Arabic Chicken Fried Mandi (1 Piece)")
                        put("qty", 1)
                        put("unitPrice", 220)
                        put("lineTotal", 220)
                    },
                ),
            )
        }
        val payload = KotPayload.fromJson(json)
        assertTrue(payload.type == "delivery")
        assertTrue(payload.phone == "9876500000")

        val preview = TicketBuilder.previewBill(payload, charsPerLine, restaurantName)
        println("=== Mahesh Bill #40 preview (CUSTOMER_BILL) ===\n$preview\n=== end preview ===")

        assertTrue(preview.contains("DELIVERY BILL"))
        assertFalse(preview.lines().any { it.trim() == "BILL" })
        assertTrue(preview.contains("Phone: 9876500000"))
        assertTrue(preview.contains("Placeholder Address"))
        assertTrue(preview.contains("TOTAL DUE"))
        assertTrue(preview.contains("Payment Status: PENDING"))
        assertTrue(preview.contains("AMOUNT TO COLLECT"))
        assertTrue(preview.contains("Rs. 220"))
    }
}
