package com.yourssu.focuswave.server

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue

class SseResponse(
    private val eventQueue: SseEventQueue
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
                val chunk = eventQueue.takeChunk()
                outputStream.write(Integer.toHexString(chunk.size).toByteArray(StandardCharsets.US_ASCII))
                outputStream.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                outputStream.write(chunk)
                outputStream.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                outputStream.flush()
            }
        } catch (_: IOException) {
            // Client disconnected; stop the SSE write loop.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

class SseEventQueue {
    // SSE 전송 스레드는 take()로 대기하고, 다른 요청 처리 스레드는 offer()로 이벤트를 넣는다.
    private val chunks = LinkedBlockingQueue<ByteArray>()

    // 브라우저 JS 이벤트로 전달되지 않는 SSE comment를 큐에 넣는다.
    fun enqueueComment(comment: String) {
        enqueue(": $comment\n\n")
    }

    // addEventListener(eventName)로 받을 수 있는 이름 있는 SSE 이벤트를 큐에 넣는다.
    fun enqueueNamedEvent(eventName: String, data: String) {
        enqueue("event: $eventName\ndata: $data\n\n")
    }

    // onmessage로 받을 수 있는 기본 message 이벤트를 큐에 넣는다.
    fun enqueueDefaultEvent(data: String) {
        enqueue("data: $data\n\n")
    }

    // 큐가 비어 있으면 이벤트가 들어올 때까지 SSE 전송 스레드를 block한다.
    @Throws(InterruptedException::class)
    fun takeChunk(): ByteArray = chunks.take()

    // SSE 포맷 문자열을 UTF-8 바이트로 바꿔 thread-safe 큐에 추가한다.
    private fun enqueue(payload: String) {
        chunks.offer(payload.toByteArray(Charsets.UTF_8))
    }
}
