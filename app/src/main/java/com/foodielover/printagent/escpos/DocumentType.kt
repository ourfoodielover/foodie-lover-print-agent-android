package com.foodielover.printagent.escpos

import com.foodielover.printagent.network.PrintJob

/**
 * What kind of physical document a print_jobs row represents. This is the one routing
 * decision PrintService makes before formatting -- see PrintService.processJob().
 *
 * KITCHEN_TICKET   -> TicketBuilder.buildKot()      (unchanged, regression-protected)
 * CUSTOMER_BILL    -> TicketBuilder.buildBill()      (new)
 * CUSTOMER_RECEIPT -> TicketBuilder.buildReceipt()   (new/replaces the old stub)
 * UNKNOWN          -> no formatter is called; the job is failed safely and printing continues.
 */
enum class DocumentType {
    KITCHEN_TICKET,
    CUSTOMER_BILL,
    CUSTOMER_RECEIPT,
    UNKNOWN,
}

/**
 * Resolution order -- see the final report for the full rationale:
 *
 * 1. payload.documentType, if present, is authoritative. Only the three recognized values
 *    above route anywhere; anything else (a typo, a future type this build doesn't know about
 *    yet) resolves to UNKNOWN. An explicit-but-unrecognized type must NEVER silently become a
 *    KITCHEN_TICKET -- that is the one rule this function exists to enforce.
 *
 * 2. If payload.documentType is absent, this is a legacy job -- fall back to the existing
 *    job_type column, exactly matching PrintService's behavior before this change:
 *      job_type == "receipt" -> CUSTOMER_RECEIPT
 *      anything else         -> KITCHEN_TICKET   (job_type is NOT NULL DEFAULT 'kot' at the DB
 *                                                  level -- see migration_010.sql -- so this is
 *                                                  a defensive default, not the common case)
 *
 * Note job_type itself is DB-CHECK-constrained to ('kot', 'receipt') only (migration_010.sql)
 * -- a server change cannot add job_type = 'bill' without a migration. That is exactly why
 * CUSTOMER_BILL can only be reached via payload.documentType, never via job_type.
 */
fun resolveDocumentType(job: PrintJob): DocumentType {
    val explicit = job.payload.documentType?.trim()?.uppercase()
    if (!explicit.isNullOrEmpty()) {
        return when (explicit) {
            "KITCHEN_TICKET" -> DocumentType.KITCHEN_TICKET
            "CUSTOMER_BILL" -> DocumentType.CUSTOMER_BILL
            "CUSTOMER_RECEIPT" -> DocumentType.CUSTOMER_RECEIPT
            else -> DocumentType.UNKNOWN
        }
    }
    return when (job.jobType) {
        "receipt" -> DocumentType.CUSTOMER_RECEIPT
        else -> DocumentType.KITCHEN_TICKET
    }
}

/** Thrown by buildBill()/buildReceipt() when a payload is missing data required to safely
 *  print a financial document (see TicketBuilder), and by PrintService when a job resolves to
 *  DocumentType.UNKNOWN. Always caught by PrintService.processJob()'s existing try/catch --
 *  exactly like any other print failure, the job is marked "failed" with this message and the
 *  poll loop continues to the next job. Never lets a malformed financial payload crash the
 *  service or fall back to printing a kitchen ticket. */
class TicketFormatException(message: String) : Exception(message)
