package com.speedcam.app.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.speedcam.core.data.HazardReportEntity
import com.speedcam.core.data.HazardReportType
import com.speedcam.core.data.ReportSyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HazardReportSenderTest {
    private val gson = Gson()

    private fun sampleReport() =
        HazardReportEntity(
            id = 101L,
            createdAt = 1700000000000L,
            lat = 55.75,
            lon = 37.61,
            headingDeg = 180.0,
            cameraId = 42L,
            reportType = HazardReportType.WRONG_SPEED_LIMIT.ordinal,
            speedLimitKmh = 90,
            comment = "Speed limit sign says 90",
            status = ReportSyncStatus.PENDING.ordinal,
            attempts = 0,
        )

    @Test
    fun `mock endpoint URL returns immediate simulation acknowledgment`() =
        runBlocking {
            val sender = HttpHazardReportSender(endpointUrl = "mock")
            val result = sender.send(sampleReport())

            assertTrue(result.isSuccess)
            val ack = result.getOrThrow()
            assertTrue(ack.startsWith("mock-ack-101"))
        }

    @Test
    fun `successful HTTP POST transmits report JSON and parses remote ID`() =
        runBlocking {
            val loopback = InetAddress.getByName("127.0.0.1")
            val serverSocket = ServerSocket(0, 1, loopback)
            val port = serverSocket.localPort
            val latch = CountDownLatch(1)
            var receivedBody = ""

            thread {
                try {
                    val client = serverSocket.accept()
                    val input = client.getInputStream()
                    val raw = StringBuilder()
                    val temp = ByteArray(1024)
                    var finished = false
                    while (!finished) {
                        val count = input.read(temp)
                        if (count > 0) {
                            raw.append(String(temp, 0, count, Charsets.UTF_8))
                            if (raw.contains("}")) {
                                finished = true
                            }
                        } else {
                            finished = true
                        }
                    }
                    receivedBody = raw.toString().substringAfter("\r\n\r\n")

                    val resp = """{"id": "remote-ack-100"}"""
                    val respBytes = resp.toByteArray(Charsets.UTF_8)
                    val header =
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${respBytes.size}\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(header.toByteArray(Charsets.UTF_8) + respBytes)
                    client.getOutputStream().flush()
                    client.close()
                } finally {
                    latch.countDown()
                }
            }

            try {
                val sender = HttpHazardReportSender(endpointUrl = "http://127.0.0.1:$port/api/hazard")
                val result = sender.send(sampleReport())

                assertTrue(latch.await(5, TimeUnit.SECONDS))
                assertTrue(result.isSuccess, "sender failed: ${result.exceptionOrNull()}")
                assertEquals("remote-ack-100", result.getOrThrow())

                val received = gson.fromJson(receivedBody, JsonObject::class.java)
                assertEquals(101L, received.get("id").asLong)
                assertEquals(55.75, received.get("lat").asDouble)
                assertEquals(37.61, received.get("lon").asDouble)
                assertEquals(90, received.get("speed_limit_kmh").asInt)
                assertEquals("Speed limit sign says 90", received.get("comment").asString)
            } finally {
                serverSocket.close()
            }
        }

    @Test
    fun `server error returns failure result`() =
        runBlocking {
            val loopback = InetAddress.getByName("127.0.0.1")
            val serverSocket = ServerSocket(0, 1, loopback)
            val port = serverSocket.localPort

            thread {
                try {
                    val client = serverSocket.accept()
                    val response =
                        "HTTP/1.1 500 Internal Server Error\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    client.getOutputStream().flush()
                    client.close()
                } catch (_: Exception) {
                }
            }

            try {
                val sender = HttpHazardReportSender(endpointUrl = "http://127.0.0.1:$port/api/hazard")
                val result = sender.send(sampleReport())
                assertTrue(result.isFailure)
            } finally {
                serverSocket.close()
            }
        }
}
