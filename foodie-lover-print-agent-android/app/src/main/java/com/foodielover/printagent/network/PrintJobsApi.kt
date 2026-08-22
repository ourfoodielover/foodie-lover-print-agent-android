package com.foodielover.printagent.network

import com.foodielover.printagent.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Talks to the EXISTING GET /api/print-jobs and PATCH /api/print-jobs/[id] endpoints --
 * same contract as print-agent/index.js's apiGet()/apiPatch(). No server changes needed;
 * this is a second HTTP client speaking the same shared-secret header.
 */
class PrintJobsApi(private val config: AppConfig) {

    class ApiException(message: String) : Exception(message)

    private fun openConnection(path: String, method: String): HttpURLConnection {
        val url = URL("${config.normalizedBaseUrl()}$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        // Never log this header's value -- mirrors index.js's describeResponse() discipline
        // of never printing PRINT_AGENT_KEY anywhere.
        conn.setRequestProperty("x-print-agent-key", config.printAgentKey)
        return conn
    }

    /** Describes a non-JSON / error response without ever including the request header
     *  value -- port of describeResponse() in index.js. */
    private fun describeResponse(method: String, path: String, conn: HttpURLConnection, body: String): String {
        val contentType = conn.contentType ?: "(no content-type)"
        val isHtml = contentType.contains("text/html") || body.trimStart().startsWith("<")
        return if (isHtml) {
            "$method $path -> HTTP ${conn.responseCode} | content-type: $contentType | " +
                "response is HTML, not JSON. Check APP_BASE_URL (\"${config.normalizedBaseUrl()}\") -- " +
                "the server may be returning an error page or auth redirect instead of the API."
        } else {
            "$method $path -> HTTP ${conn.responseCode} | content-type: $contentType | " +
                body.take(200)
        }
    }

    private fun readStream(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
    }

    /** GET /api/print-jobs?restaurantId=..&printerId=..&limit=20 */
    suspend fun fetchJobs(): List<PrintJob> = withContext(Dispatchers.IO) {
        val query = "restaurantId=${enc(config.restaurantId)}&printerId=${enc(config.stationId)}&limit=20"
        val path = "/api/print-jobs?$query"
        val conn = openConnection(path, "GET")
        try {
            val body = readStream(conn)
            val contentType = conn.contentType ?: ""
            if (conn.responseCode !in 200..299 || !contentType.contains("application/json")) {
                throw ApiException(describeResponse("GET", path, conn, body))
            }
            val arr = JSONArray(body)
            val jobs = mutableListOf<PrintJob>()
            for (i in 0 until arr.length()) {
                // A single malformed row must not take down the whole poll cycle.
                runCatching { jobs.add(PrintJob.fromJson(arr.getJSONObject(i))) }
            }
            jobs
        } finally {
            conn.disconnect()
        }
    }

    /** PATCH /api/print-jobs/[id] { status, error? } */
    suspend fun updateJobStatus(jobId: String, status: String, error: String? = null): Unit =
        withContext(Dispatchers.IO) {
            val path = "/api/print-jobs/$jobId"
            val conn = openConnection(path, "PATCH")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().apply {
                put("status", status)
                if (error != null) put("error", error)
            }
            try {
                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                    it.write(body.toString())
                }
                val respBody = readStream(conn)
                val contentType = conn.contentType ?: ""
                if (conn.responseCode !in 200..299 || !contentType.contains("application/json")) {
                    throw ApiException(describeResponse("PATCH", path, conn, respBody))
                }
            } finally {
                conn.disconnect()
            }
        }

    // NOTE: deliberately no separate "check server" call. The single poll loop in
    // PrintService derives Server: Connected/Disconnected from the outcome of its own
    // fetchJobs() call each cycle -- a second reachability call here would just be a second,
    // redundant poller, which is exactly what the single-poller rule forbids.

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
