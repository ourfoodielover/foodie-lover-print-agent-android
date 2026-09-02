package com.foodielover.printagent.escpos

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Byte-for-byte port of the ESC/POS command table in print-agent/index.js.
 * Do not "improve" these — the goal is a Bluetooth ticket that matches the Windows/USB
 * ticket exactly, since the CN811 has only been proven against these specific bytes
 * (Phase 0 physical test) and the printer's default codepage assumptions.
 */
object EscPos {
    private const val ESC = 0x1B
    private const val GS = 0x1D

    val INIT = byteArrayOf(ESC.toByte(), 0x40)
    val BOLD_ON = byteArrayOf(ESC.toByte(), 0x45, 1)
    val BOLD_OFF = byteArrayOf(ESC.toByte(), 0x45, 0)
    val ALIGN_LEFT = byteArrayOf(ESC.toByte(), 0x61, 0)
    val ALIGN_CENTER = byteArrayOf(ESC.toByte(), 0x61, 1)
    val DOUBLE_ON = byteArrayOf(GS.toByte(), 0x21, 0x11)
    val DOUBLE_OFF = byteArrayOf(GS.toByte(), 0x21, 0x00)
    val CUT = byteArrayOf(GS.toByte(), 0x56, 0x42, 0x00)
    fun feed(n: Int): ByteArray = byteArrayOf(ESC.toByte(), 0x64, n.toByte())
}

/** Accumulates ESC/POS bytes the same way index.js builds up a Buffer via Buffer.concat.
 *
 *  Also -- purely additively -- keeps a parallel plain-text log of every line() call. raw()
 *  (ESC/POS control bytes) never touches it. This costs nothing behaviorally: toByteArray()'s
 *  output is byte-for-byte identical to before, and buildKot()/buildTestTicket() never read
 *  previewText(), so this has zero effect on the regression-protected KOT path. It exists so
 *  the DEBUG-only dry-run diagnostics (see debug/JobDiagnostics.kt) can show what a financial
 *  document would look like without ever touching Bluetooth. */
class TicketBuffer {
    private val out = ByteArrayOutputStream()
    private val previewLines = mutableListOf<String>()

    fun raw(bytes: ByteArray): TicketBuffer {
        out.write(bytes)
        return this
    }

    /** Mirrors index.js `line(text)` -- UTF-8 text followed by a bare '\n'. */
    fun line(text: String = ""): TicketBuffer {
        out.write(text.toByteArray(StandardCharsets.UTF_8))
        out.write('\n'.code)
        previewLines.add(text)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    /** Plain-text preview -- every line() call's text, one per line, in order. Never includes
     *  raw ESC/POS control bytes since those never go through line(). */
    fun previewText(): String = previewLines.joinToString("\n")
}
