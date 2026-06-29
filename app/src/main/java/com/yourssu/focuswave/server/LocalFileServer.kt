package com.yourssu.focuswave.server

import fi.iki.elonen.NanoHTTPD

class LocalFileServer : NanoHTTPD(PORT) {
    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.GET) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Method not allowed"
            )
        }

        return when (session.uri) {
            "/" -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                HOME_PAGE
            )

            "/list" -> newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                "[]"
            )

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not found"
            )
        }
    }

    companion object {
        const val PORT = 8080

        private val HOME_PAGE = """
            <!doctype html>
            <html lang="ko">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>FocusWave Local Share</title>
                <style>
                    body {
                        font-family: sans-serif;
                        max-width: 680px;
                        margin: 64px auto;
                        padding: 0 24px;
                        color: #202124;
                    }
                    .card {
                        border: 1px solid #dadce0;
                        border-radius: 16px;
                        padding: 24px;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>FocusWave Local Share</h1>
                    <p>같은 Wi-Fi에서 FocusWave 로컬 서버에 연결되었습니다.</p>
                    <p>파일 업로드와 다운로드는 다음 단계에서 제공됩니다.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
