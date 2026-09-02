package com.foodielover.printagent.debug

import com.foodielover.printagent.config.AppConfig
import com.foodielover.printagent.escpos.DocumentType
import com.foodielover.printagent.escpos.TicketBuilder
import com.foodielover.printagent.escpos.resolveDocumentType
import com.foodielover.printagent.network.PrintJob
import com.foodielover.printagent.network.PrintJobsApi

/**
 * DEBUG-ONLY dry-run diagnostics for CUSTOMER_BILL / CUSTOMER_RECEIPT jobs -- verifies a job
 * all the way through Android's document-routing and formatting WITHOUT sending a single byte
 * to the printer and WITHOUT changing any job's server-side status.
 *
 * Safety guarantees (do not remove without re-reading why they're here):
 *  - Calls PrintJobsApi.fetchJobs() only -- a plain GET, exactly what the real poll loop calls
 *    to list jobs. Never calls PrintJobsApi.updateJobStatus() (no PATCH), so a real queued
 *    production job is never marked "printing"/"printed"/"failed" just because someone
 *    previewed it, and it is never "consumed" -- it stays queued for the real service loop.
 *  - Never touches BluetoothPrinterManager. No Bluetooth connection is opened, no bytes are
 *    written to the CN811.
 *  - Not wired into PrintService, the poll loop, or any of its lifecycle -- calling this can
 *    never start a second poller.
 *  - Only meant to be reachable from a UI element gated on BuildConfig.DEBUG (see
 *    MainActivity's debug diagnostics button).
 *  - Does not print/log the customer's phone or delivery address on their own -- the preview
 *    text mirrors exactly what buildBill()/buildReceipt() would physically print (which does
 *    include phone/address, same as the real ticket would), meant for on-screen display in the
 *    diagnostics dialog, not for writing to Logcat.
 */
object JobDiagnostics {

    data class JobDiagnosis(
        val jobId: String,
        val jobType: String,
        val documentType: String?,
        val printerId: String?,
        val resolvedType: DocumentType,
        val formatterName: String,
        val success: Boolean,
        val errorMessage: String?,
        val byteCount: Int?,
        val previewText: String?,
    )

    /** Read-only: fetches the current queued/failed jobs (same GET the real poll loop makes)
     *  and runs each one through the exact same resolveDocumentType()/formatter selection
     *  PrintService.processJob() uses, without printing or changing any job's status. */
    suspend fun dryRun(config: AppConfig): List<JobDiagnosis> {
        val api = PrintJobsApi(config)
        val jobs = api.fetchJobs() // GET only -- does not claim/consume any job
        return jobs.map { diagnoseOne(it, config) }
    }

    private fun diagnoseOne(job: PrintJob, config: AppConfig): JobDiagnosis {
        val resolved = resolveDocumentType(job)
        val formatterName = when (resolved) {
            DocumentType.KITCHEN_TICKET -> "buildKot()"
            DocumentType.CUSTOMER_BILL -> "buildBill()"
            DocumentType.CUSTOMER_RECEIPT -> "buildReceipt()"
            DocumentType.UNKNOWN -> "(none -- unrecognized document type, refused to print)"
        }
        return try {
            val (preview, byteCount) = when (resolved) {
                DocumentType.KITCHEN_TICKET -> {
                    // KOT formatting/output is not exercised here -- Test Print already covers
                    // it, and this dry-run exists specifically for the financial formatters.
                    "(Kitchen Order Ticket -- use Test Print to verify buildKot() output)" to null
                }
                DocumentType.CUSTOMER_BILL -> {
                    val text = TicketBuilder.previewBill(job.payload, config.charsPerLine, config.restaurantName, job.isReprint)
                    val bytes = TicketBuilder.buildBill(job.payload, config.charsPerLine, config.restaurantName, job.isReprint)
                    text to bytes.size
                }
                DocumentType.CUSTOMER_RECEIPT -> {
                    val text = TicketBuilder.previewReceipt(job.payload, config.charsPerLine, config.restaurantName, job.isReprint)
                    val bytes = TicketBuilder.buildReceipt(job.payload, config.charsPerLine, config.restaurantName, job.isReprint)
                    text to bytes.size
                }
                DocumentType.UNKNOWN -> throw IllegalStateException(
                    "Unrecognized document type -- job_type=\"${job.jobType}\", " +
                        "payload.documentType=\"${job.payload.documentType}\"",
                )
            }
            JobDiagnosis(
                jobId = job.id, jobType = job.jobType, documentType = job.payload.documentType,
                printerId = job.printerId, resolvedType = resolved, formatterName = formatterName,
                success = true, errorMessage = null, byteCount = byteCount, previewText = preview,
            )
        } catch (e: Exception) {
            JobDiagnosis(
                jobId = job.id, jobType = job.jobType, documentType = job.payload.documentType,
                printerId = job.printerId, resolvedType = resolved, formatterName = formatterName,
                success = false, errorMessage = e.message ?: e.toString(), byteCount = null, previewText = null,
            )
        }
    }
}
