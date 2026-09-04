package com.foodielover.printagent.network

import com.foodielover.printagent.escpos.DocumentType
import com.foodielover.printagent.escpos.resolveDocumentType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the payload-contract fix in KotPayload.fromJson() (network/models.kt): the
 * CUSTOMER_BILL / CUSTOMER_RECEIPT payload builder sends "orderType" / "customerPhone", while
 * legacy KOT payloads (buildKotPayload() in app/api/orders/[id]/route.ts) send "type" / "phone".
 * Both must parse into KotPayload.type / KotPayload.phone, canonical (financial) key first.
 *
 * This is a parser-only test file -- it never touches TicketBuilder, Bluetooth, or the network.
 * See TicketBuilderFinancialTest for the rendering-side assertions (test matrix items 1-5, 8's
 * printed-output half) and the real-example verification.
 */
class KotPayloadParsingTest {

    private fun minimalItem() = JSONObject().apply {
        put("name", "Test Item")
        put("qty", 1)
        put("unitPrice", 100)
        put("lineTotal", 100)
    }

    private fun payloadJson(fields: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        obj.put("orderId", "ORD_TEST")
        obj.put("items", org.json.JSONArray().put(minimalItem()))
        for ((k, v) in fields) {
            if (v == null) obj.put(k, JSONObject.NULL) else obj.put(k, v)
        }
        return obj
    }

    // ── Matrix 1/2: canonical financial keys (orderType, customerPhone) ────────────────────

    @Test
    fun `canonical orderType and customerPhone are parsed`() {
        val json = payloadJson(mapOf("orderType" to "delivery", "customerPhone" to "9052797254"))
        val payload = KotPayload.fromJson(json)
        assertEquals("delivery", payload.type)
        assertEquals("9052797254", payload.phone)
    }

    // ── Matrix 3/4: pickup ───────────────────────────────────────────────────────────────

    @Test
    fun `pickup orderType is parsed`() {
        val json = payloadJson(mapOf("orderType" to "pickup", "customerPhone" to "9052797254"))
        val payload = KotPayload.fromJson(json)
        assertEquals("pickup", payload.type)
        assertEquals("9052797254", payload.phone)
    }

    // ── Matrix 5: dine-in ────────────────────────────────────────────────────────────────

    @Test
    fun `dine-in orderType is parsed and phone is optional`() {
        val json = payloadJson(mapOf("orderType" to "dine-in"))
        val payload = KotPayload.fromJson(json)
        assertEquals("dine-in", payload.type)
        assertNull(payload.phone)
    }

    // ── Matrix 6: legacy KOT keys (type, phone) still parse -- regression protection ───────

    @Test
    fun `legacy type and phone keys still parse (KOT regression)`() {
        val json = payloadJson(mapOf("type" to "delivery", "phone" to "9999999999"))
        val payload = KotPayload.fromJson(json)
        assertEquals("delivery", payload.type)
        assertEquals("9999999999", payload.phone)
    }

    @Test
    fun `legacy KOT payload with only 'type', no phone at all, parses exactly as before`() {
        // Mirrors an actual buildKotPayload() JSON shape: no documentType, no customerPhone,
        // no phone -- only "type". Must not throw, and phone must stay null.
        val json = payloadJson(mapOf("type" to "pickup"))
        val payload = KotPayload.fromJson(json)
        assertEquals("pickup", payload.type)
        assertNull(payload.phone)
    }

    // ── Matrix 7: both aliases present -- canonical (financial) key wins ───────────────────

    @Test
    fun `when both orderType and type are present, orderType wins`() {
        val json = payloadJson(mapOf("orderType" to "delivery", "type" to "dine-in"))
        val payload = KotPayload.fromJson(json)
        assertEquals("delivery", payload.type)
    }

    @Test
    fun `when both customerPhone and phone are present, customerPhone wins`() {
        val json = payloadJson(mapOf("customerPhone" to "1111111111", "phone" to "2222222222"))
        val payload = KotPayload.fromJson(json)
        assertEquals("1111111111", payload.phone)
    }

    // ── Matrix 8: missing customerPhone -- no null-as-string, no blank value ───────────────

    @Test
    fun `missing phone fields parse to null, never the string 'null' or blank`() {
        val json = payloadJson(emptyMap())
        val payload = KotPayload.fromJson(json)
        assertNull(payload.phone)
    }

    @Test
    fun `explicit JSON null for customerPhone parses to null, not the string 'null'`() {
        val json = payloadJson(mapOf("customerPhone" to null))
        val payload = KotPayload.fromJson(json)
        assertNull(payload.phone)
    }

    @Test
    fun `blank-string customerPhone parses to null, not a blank Phone line`() {
        val json = payloadJson(mapOf("customerPhone" to "   "))
        val payload = KotPayload.fromJson(json)
        assertNull(payload.phone)
    }

    // ── Never parsed from customerName ──────────────────────────────────────────────────────

    @Test
    fun `phone is never derived from customerName, even when customerName contains digits`() {
        val json = payloadJson(
            mapOf(
                "customerName" to "KJayalaxmi 9052797254 Sarda.pg",
                // no customerPhone, no phone at all
            ),
        )
        val payload = KotPayload.fromJson(json)
        assertNull(payload.phone)
    }

    // ── Sanity check: documentType resolution is untouched by this fix ─────────────────────

    @Test
    fun `CUSTOMER_BILL documentType still resolves correctly alongside the new orderType field`() {
        val json = payloadJson(mapOf("documentType" to "CUSTOMER_BILL", "orderType" to "delivery"))
        val payload = KotPayload.fromJson(json)
        val job = PrintJob(
            id = "job1",
            orderId = "ORD_TEST",
            jobType = "receipt",
            status = "queued",
            printerId = "default",
            payload = payload,
            attempts = 0,
            isReprint = false,
            createdAt = null,
        )
        assertEquals(DocumentType.CUSTOMER_BILL, resolveDocumentType(job))
    }
}
