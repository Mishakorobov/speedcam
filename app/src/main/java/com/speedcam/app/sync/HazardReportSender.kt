package com.speedcam.app.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.speedcam.core.data.HazardReportEntity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Delivery mechanism for crowdsourced hazard submissions (AREAS_FOR_DEVELOPMENT.md [M-30]). */
fun interface HazardReportSender {
    suspend fun send(report: HazardReportEntity): Result<String>
}

/** Standard HTTP client uploading reports via JSON POST. */
class HttpHazardReportSender(
    private val endpointUrl: String,
    private val gson: Gson = Gson(),
) : HazardReportSender {
    override suspend fun send(report: HazardReportEntity): Result<String> =
        runCatching {
            if (endpointUrl.isBlank() || endpointUrl.startsWith("mock:", ignoreCase = true) || endpointUrl == "mock") {
                // Simulated upload acknowledgment for testing or offline mode without live backend
                return@runCatching "mock-ack-${report.id}-${System.currentTimeMillis()}"
            }

            val payload =
                buildMap {
                    put("id", report.id)
                    put("created_at", report.createdAt)
                    put("lat", report.lat)
                    put("lon", report.lon)
                    report.headingDeg?.let { put("heading_deg", it) }
                    report.cameraId?.let { put("camera_id", it) }
                    put("report_type", report.reportType)
                    report.speedLimitKmh?.let { put("speed_limit_kmh", it) }
                    report.comment?.let { put("comment", it) }
                }
            val json = gson.toJson(payload)

            val connection =
                (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    instanceFollowRedirects = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("User-Agent", USER_AGENT)
                }

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code from $endpointUrl: ${connection.responseMessage}")
            }

            val responseBody = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val remoteId =
                runCatching {
                    gson.fromJson(responseBody, JsonObject::class.java).get("id")?.asString
                }.getOrNull()
            remoteId?.ifBlank { null } ?: "server-ack-${report.id}"
        }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val USER_AGENT = "SpeedCam-Android/1.0"
        const val DEFAULT_HAZARD_URL = "https://speedcam-hazards.example.com/api/v1/hazards"
    }
}
