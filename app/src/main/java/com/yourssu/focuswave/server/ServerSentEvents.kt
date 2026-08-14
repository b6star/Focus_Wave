package com.yourssu.focuswave.server

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue

class SseResponse(
    private val stream: SseEventStream
) : NanoHTTPD.Response(
    NanoHTTPD.Response.Status.OK,
    "text/event-stream; charset=utf-8",
    null,
    -1
) {
    override fun send(outputStream: OutputStream) {
        try {
            outputStream.write(
                (
                    "HTTP/1.1 ${NanoHTTPD.Response.Status.OK.description} \r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-cache, no-transform\r\n" +
                        "Connection: keep-alive\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "X-Accel-Buffering: no\r\n" +
                        "\r\n"
                    ).toByteArray(StandardCharsets.UTF_8)
            )
            outputStream.flush()

            while (true) {
                val chunk = stream.takeChunk()
                outputStream.write(Integer.toHexString(chunk.size).toByteArray(StandardCharsets.US_ASCII))
                outputStream.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                outputStream.write(chunk)
                outputStream.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                outputStream.flush()
            }
        } catch (_: IOException) {
            stream.close()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            stream.close()
        }
    }
}

class SseEventStream : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var currentChunk = ByteArray(0)
    private var currentIndex = 0

    fun sendComment(comment: String) {
        enqueue(": $comment\n\n")
    }

    fun sendEvent(event: String, data: String) {
        enqueue("event: $event\ndata: $data\n\n")
    }

    fun sendMessage(data: String) {
        enqueue("data: $data\n\n")
    }

    @Throws(InterruptedException::class)
    fun takeChunk(): ByteArray = chunks.take()

    override fun read(): Int {
        while (currentIndex >= currentChunk.size) {
            currentChunk = chunks.take()
            currentIndex = 0
        }

        return currentChunk[currentIndex++].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val firstByte = read()
        if (firstByte < 0) return -1

        buffer[offset] = firstByte.toByte()
        var copied = 1

        while (copied < length && currentIndex < currentChunk.size) {
            buffer[offset + copied] = currentChunk[currentIndex++]
            copied++
        }

        return copied
    }

    private fun enqueue(payload: String) {
        chunks.offer(payload.toByteArray(Charsets.UTF_8))
    }
}
