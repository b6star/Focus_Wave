package com.yourssu.focuswave.server

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileServerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: LocalFileServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = LocalFileServer(temporaryFolder.root, 0)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        baseUrl = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun upload_savesFileAndReturnsMetadata() {
        val response = upload("notes.txt", "focus wave".toByteArray())

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
        assertEquals("focus wave", sharedFile("notes.txt").readText())
        assertTrue(response.body.contains("\"success\":true"))
        assertTrue(response.body.contains("\"fileName\":\"notes.txt\""))
        assertTrue(response.body.contains("\"size\":10"))
    }

    @Test
    fun upload_preservesExistingFileWithNumberedName() {
        upload("notes.txt", "first".toByteArray())
        val response = upload("notes.txt", "second".toByteArray())

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
        assertEquals("first", sharedFile("notes.txt").readText())
        assertEquals("second", sharedFile("notes (1).txt").readText())
        assertTrue(response.body.contains("\"fileName\":\"notes (1).txt\""))
    }

    @Test
    fun upload_stripsDirectoriesFromFileName() {
        val response = upload("../../secret.txt", "safe".toByteArray())

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode)
        assertEquals("safe", sharedFile("secret.txt").readText())
        assertFalse(File(temporaryFolder.root.parentFile, "secret.txt").exists())
    }

    @Test
    fun upload_rejectsNonMultipartRequest() {
        val connection = (URL("$baseUrl/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain")
        }
        connection.outputStream.use { it.write("not multipart".toByteArray()) }

        val response = readResponse(connection)

        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, response.statusCode)
        assertTrue(response.body.contains("\"success\":false"))
    }

    private fun upload(fileName: String, content: ByteArray): HttpResponse {
        val boundary = "----FocusWaveTestBoundary"
        val header = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
        val body = ByteArrayOutputStream().use { output ->
            output.write(header)
            output.write(content)
            output.write(footer)
            output.toByteArray()
        }

        val connection = (URL("$baseUrl/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        connection.outputStream.use { it.write(body) }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): HttpResponse {
        val statusCode = connection.responseCode
        val stream = if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
            connection.errorStream
        } else {
            connection.inputStream
        }
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return HttpResponse(statusCode, body)
    }

    private fun sharedFile(name: String): File =
        File(File(temporaryFolder.root, "shared_files"), name)

    private data class HttpResponse(
        val statusCode: Int,
        val body: String
    )
}
