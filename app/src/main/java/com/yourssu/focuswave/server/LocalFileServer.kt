package com.yourssu.focuswave.server

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.text.Normalizer

class LocalFileServer(
    private val appFilesDirectory: File,
    port: Int = PORT
) : NanoHTTPD(port) {
    private val fileSaveLock = Any()

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.uri == "/" && session.method == Method.GET -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                HOME_PAGE
            )

            session.uri == "/list" && session.method == Method.GET -> newFixedLengthResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                "[]"
            )

            session.uri == "/upload" && session.method == Method.POST -> handleUpload(session)

            session.uri == "/upload" || session.method != Method.GET -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Method not allowed"
            )

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_PLAINTEXT,
                "Not found"
            )
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val contentType = session.headers["content-type"].orEmpty()
        val mediaType = contentType.substringBefore(';').trim()
        if (!mediaType.equals("multipart/form-data", ignoreCase = true)) {
            return uploadError(Response.Status.BAD_REQUEST, "Content-Type must be multipart/form-data")
        }

        val contentLength = session.headers["content-length"]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_REQUEST_SIZE_BYTES) {
            return uploadError(Response.Status.PAYLOAD_TOO_LARGE, "File is too large")
        }

        val uploadedParts = mutableMapOf<String, String>()
        try {
            session.parseBody(uploadedParts)
        } catch (error: Exception) {
            return uploadError(
                Response.Status.BAD_REQUEST,
                error.localizedMessage ?: "Invalid multipart request"
            )
        }

        val rawFileName = session.parms[UPLOAD_FIELD_NAME]
        val temporaryPath = uploadedParts[UPLOAD_FIELD_NAME]
        if (rawFileName.isNullOrBlank() || temporaryPath.isNullOrBlank()) {
            return uploadError(Response.Status.BAD_REQUEST, "File field is required")
        }

        val safeFileName = sanitizeFileName(rawFileName)
            ?: return uploadError(Response.Status.BAD_REQUEST, "File name is empty")
        val temporaryFile = File(temporaryPath)
        if (!temporaryFile.isFile) {
            return uploadError(Response.Status.BAD_REQUEST, "Uploaded file is invalid")
        }
        if (temporaryFile.length() > MAX_FILE_SIZE_BYTES) {
            return uploadError(Response.Status.PAYLOAD_TOO_LARGE, "File is too large")
        }

        return try {
            val storedFile = saveUploadedFile(temporaryFile, safeFileName)
            jsonResponse(
                Response.Status.OK,
                """{"success":true,"fileName":${jsonString(storedFile.name)},"size":${storedFile.length()}}"""
            )
        } catch (error: IOException) {
            uploadError(
                Response.Status.INTERNAL_ERROR,
                "Failed to save file"
            )
        }
    }

    private fun getOrCreateSharedDirectory(): File {
        val directory = File(appFilesDirectory, SHARED_DIRECTORY_NAME)
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("Shared storage path is not a directory")
            }
        } else if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Failed to create shared storage directory")
        }
        return directory
    }

    private fun sanitizeFileName(rawFileName: String): String? {
        val leafName = rawFileName
            .replace('\\', '/')
            .substringAfterLast('/')
        val normalizedName = Normalizer.normalize(leafName, Normalizer.Form.NFC)
        val cleanedName = normalizedName
            .replace(CONTROL_CHARACTERS, "")
            .replace(UNSAFE_FILE_NAME_CHARACTERS, "_")
            .trim()
            .trim('.')
            .take(MAX_FILE_NAME_CHARACTERS)
            .trimEnd { Character.isHighSurrogate(it) }

        return cleanedName.ifBlank { null }
    }

    private fun resolveUniqueFile(directory: File, safeFileName: String): File {
        val initialFile = File(directory, safeFileName)
        if (!initialFile.exists()) return initialFile

        val dotIndex = safeFileName.lastIndexOf('.')
        val extension = if (dotIndex > 0) safeFileName.substring(dotIndex) else ""
        val baseName = if (dotIndex > 0) safeFileName.substring(0, dotIndex) else safeFileName

        var suffixNumber = 1
        while (true) {
            val suffix = " ($suffixNumber)"
            val shortenedBase = baseName
                .take((MAX_FILE_NAME_CHARACTERS - extension.length - suffix.length).coerceAtLeast(1))
                .trimEnd { Character.isHighSurrogate(it) }
            val candidate = File(directory, shortenedBase + suffix + extension)
            if (!candidate.exists()) return candidate
            suffixNumber++
        }
    }

    private fun saveUploadedFile(temporaryFile: File, safeFileName: String): File =
        synchronized(fileSaveLock) {
            val directory = getOrCreateSharedDirectory()
            val destination = resolveUniqueFile(directory, safeFileName)
            if (destination.canonicalFile.parentFile != directory.canonicalFile) {
                throw IOException("Invalid file path")
            }

            try {
                temporaryFile.copyTo(destination, overwrite = false)
            } catch (error: IOException) {
                destination.delete()
                throw error
            }
        }

    private fun uploadError(status: Response.Status, message: String): Response =
        jsonResponse(
            status,
            """{"success":false,"message":${jsonString(message)}}"""
        )

    private fun jsonResponse(status: Response.Status, body: String): Response =
        newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            body
        )

    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    companion object {
        const val PORT = 8080

        private const val UPLOAD_FIELD_NAME = "file"
        private const val SHARED_DIRECTORY_NAME = "shared_files"
        private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024
        private const val MAX_MULTIPART_OVERHEAD_BYTES = 1024L * 1024
        private const val MAX_REQUEST_SIZE_BYTES = MAX_FILE_SIZE_BYTES + MAX_MULTIPART_OVERHEAD_BYTES
        private const val MAX_FILE_NAME_CHARACTERS = 60
        private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        private val UNSAFE_FILE_NAME_CHARACTERS = Regex("""[/:*?"<>|]""")

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
                    form {
                        display: grid;
                        gap: 16px;
                        margin-top: 24px;
                    }
                    button {
                        width: fit-content;
                        padding: 10px 18px;
                        cursor: pointer;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>FocusWave Local Share</h1>
                    <p>같은 Wi-Fi에서 FocusWave 로컬 서버에 연결되었습니다.</p>
                    <p>최대 50MB의 파일을 업로드할 수 있습니다.</p>
                    <form method="post" enctype="multipart/form-data" action="/upload">
                        <input type="file" name="file" required>
                        <button type="submit">파일 업로드</button>
                    </form>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}
