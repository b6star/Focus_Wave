package com.yourssu.focuswave.server

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class LocalFileServer(
    private val appFilesDirectory: File,
    port: Int = PORT,
    private val authCode: String,
    private val homePage: String? = null,
    private val secureRandom: SecureRandom = SecureRandom()
) : NanoHTTPD(port) {
    private val fileSaveLock = Any()
    private val activeTokens = ConcurrentHashMap.newKeySet<String>()

    override fun stop() {
        activeTokens.clear()
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.uri == "/" && session.method == Method.GET -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                homePage ?: FALLBACK_HOME_PAGE
            )

            session.uri == "/ping" && session.method == Method.GET -> jsonResponse(
                Response.Status.OK,
                """{"success":true}"""
            )

            session.uri == "/auth" && session.method == Method.POST -> handleAuth(session)

            session.uri == "/list" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> handleList()
                else -> methodNotAllowedResponse()
            }

            session.uri == "/upload" -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.POST -> handleUpload(session)
                else -> methodNotAllowedResponse()
            }

            session.uri.startsWith("/download/") -> when {
                !isAuthenticated(session) -> unauthorizedResponse()
                session.method == Method.GET -> handleDownload(session.uri)
                else -> methodNotAllowedResponse()
            }

            session.uri == "/auth" || session.uri == "/ping" || session.uri == "/" ->
                methodNotAllowedResponse()

            session.method != Method.GET -> newFixedLengthResponse(
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

    private fun handleAuth(session: IHTTPSession): Response {
        val mediaType = session.headers["content-type"]
            .orEmpty()
            .substringBefore(';')
            .trim()
        if (!mediaType.equals("application/json", ignoreCase = true)) {
            return jsonError(Response.Status.BAD_REQUEST, "Content-Type must be application/json")
        }

        val parsedBody = mutableMapOf<String, String>()
        try {
            session.parseBody(parsedBody)
        } catch (_: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid JSON request")
        }

        val submittedCode = AUTH_CODE_PATTERN
            .find(parsedBody["postData"].orEmpty())
            ?.groupValues
            ?.get(1)
        if (submittedCode == null || !codesMatch(submittedCode, authCode)) {
            return unauthorizedResponse("Invalid authentication code")
        }

        val token = generateToken()
        activeTokens.add(token)
        return jsonResponse(
            Response.Status.OK,
            """{"success":true,"token":${jsonString(token)}}"""
        ).apply {
            addHeader(
                "Set-Cookie",
                "$TOKEN_COOKIE_NAME=$token; Path=/; HttpOnly; SameSite=Strict"
            )
        }
    }

    private fun generateToken(): String {
        val tokenBytes = ByteArray(TOKEN_BYTE_LENGTH)
        secureRandom.nextBytes(tokenBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
    }

    private fun codesMatch(submittedCode: String, expectedCode: String): Boolean =
        MessageDigest.isEqual(
            submittedCode.toByteArray(Charsets.UTF_8),
            expectedCode.toByteArray(Charsets.UTF_8)
        )

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        val authorizationToken = session.headers["authorization"]
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
        val customHeaderToken = session.headers[TOKEN_HEADER_NAME]?.trim()
        val cookieToken = session.headers["cookie"]
            ?.split(';')
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$TOKEN_COOKIE_NAME=") }
            ?.substringAfter('=')
            ?.trim()
        val token = authorizationToken
            ?.takeIf { it.isNotEmpty() }
            ?: customHeaderToken?.takeIf { it.isNotEmpty() }
            ?: cookieToken?.takeIf { it.isNotEmpty() }
        return token != null && activeTokens.contains(token)
    }

    private fun unauthorizedResponse(message: String = "Authentication required"): Response =
        jsonError(Response.Status.UNAUTHORIZED, message).apply {
            addHeader("WWW-Authenticate", "Bearer")
        }

    private fun methodNotAllowedResponse(): Response = newFixedLengthResponse(
        Response.Status.METHOD_NOT_ALLOWED,
        MIME_PLAINTEXT,
        "Method not allowed"
    )

    private fun handleUpload(session: IHTTPSession): Response {
        val contentType = session.headers["content-type"].orEmpty()
        val mediaType = contentType.substringBefore(';').trim()
        if (!mediaType.equals("multipart/form-data", ignoreCase = true)) {
            return uploadError(Response.Status.BAD_REQUEST, "Content-Type must be multipart/form-data")
        }

        val contentLength = session.headers["content-length"]?.toLongOrNull()

        /**
         * 파일 크기 제한
        if (contentLength != null && contentLength > MAX_REQUEST_SIZE_BYTES) {
            return uploadError(Response.Status.PAYLOAD_TOO_LARGE, "File is too large")
        }

         */

        val uploadedParts = mutableMapOf<String, String>()
        try {
            session.parseBody(uploadedParts)
        } catch (error: Exception) {
            return uploadError(
                Response.Status.BAD_REQUEST,
                error.localizedMessage ?: "Invalid multipart request"
            )
        }

        val uploadFieldName = UPLOAD_FIELD_NAMES.firstOrNull { fieldName ->
            !session.parms[fieldName].isNullOrBlank() && !uploadedParts[fieldName].isNullOrBlank()
        }
        val rawFileName = session.parameters["fileName"]?.firstOrNull()
            ?: session.parms["fileName"]
            ?: uploadFieldName?.let(session.parms::get)
        val temporaryPath = uploadFieldName?.let(uploadedParts::get)
        if (rawFileName.isNullOrBlank() || temporaryPath.isNullOrBlank()) {
            return uploadError(Response.Status.BAD_REQUEST, "File field is required")
        }

        val safeFileName = sanitizeFileName(rawFileName)
            ?: return uploadError(Response.Status.BAD_REQUEST, "File name is empty")
        val temporaryFile = File(temporaryPath)
        if (!temporaryFile.isFile) {
            return uploadError(Response.Status.BAD_REQUEST, "Uploaded file is invalid")
        }

        /**
         * 
        파일 크기 제한
        if (temporaryFile.length() > MAX_FILE_SIZE_BYTES) {
            return uploadError(Response.Status.PAYLOAD_TOO_LARGE, "File is too large")
        }
         */

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

    private fun handleList(): Response {
        return try {
            val directory = getOrCreateSharedDirectory()

            val filesJson = directory
                .listFiles()
                ?.filter { it.isFile }
                ?.joinToString(
                    prefix = "[",
                    postfix = "]"
                ) { file ->
                    jsonString(file.name)
                } ?: "[]"

            jsonResponse(Response.Status.OK, filesJson)
        } catch (error: IOException) {
            jsonError(Response.Status.INTERNAL_ERROR, "Failed to list files")
        }
    }

    private fun handleDownload(uri: String): Response {
        return try {
            val encodedFileName = uri.removePrefix("/download/")
            val decodedFileName = java.net.URLDecoder.decode(encodedFileName, "UTF-8")

            val safeFileName = sanitizeFileName(decodedFileName)
                ?: return jsonError(Response.Status.BAD_REQUEST, "Invalid file name")

            val directory = getOrCreateSharedDirectory()
            val file = File(directory, safeFileName)

            if (!file.exists() || !file.isFile) {
                return jsonError(Response.Status.NOT_FOUND, "File not found")
            }

            if (file.canonicalFile.parentFile != directory.canonicalFile) {
                return jsonError(Response.Status.BAD_REQUEST, "Invalid file path")
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "application/octet-stream",
                file.inputStream(),
                file.length()
            ).apply {
                addHeader(
                    "Content-Disposition",
                    "attachment; filename=\"${file.name}\""
                )
            }
        } catch (error: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, "Failed to download file")
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

    private fun getOrCreateUploadDirectory(): File {
        val directory = File(appFilesDirectory, UPLOAD_DIRECTORY_NAME)
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("Upload storage path is not a directory")
            }
        } else if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Failed to create upload storage directory")
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
            val directory = getOrCreateUploadDirectory()
            val destination = resolveUniqueFile(directory, safeFileName)

            if (destination.canonicalFile.parentFile != directory.canonicalFile) {
                throw IOException("Invalid file path")
            }

            try {
                temporaryFile.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                        output.flush()
                    }
                }

                if (destination.length() != temporaryFile.length()) {
                    destination.delete()
                    throw IOException("File copy incomplete")
                }

                destination
            } catch (error: IOException) {
                destination.delete()
                throw error
            }
        }

    private fun uploadError(status: Response.Status, message: String): Response =
        jsonError(status, message)

    private fun jsonError(status: Response.Status, message: String): Response =
        jsonResponse(status, """{"success":false,"message":${jsonString(message)}}""")

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

        private val UPLOAD_FIELD_NAMES = listOf("file", "files")

        //pc -> phone
        private const val UPLOAD_DIRECTORY_NAME = "uploaded_files"

        //phone -> pc
        private const val SHARED_DIRECTORY_NAME = "shared_files"
        private const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024
        private const val MAX_MULTIPART_OVERHEAD_BYTES = 1024L * 1024
        private const val MAX_REQUEST_SIZE_BYTES = MAX_FILE_SIZE_BYTES + MAX_MULTIPART_OVERHEAD_BYTES
        private const val MAX_FILE_NAME_CHARACTERS = 60
        private const val TOKEN_BYTE_LENGTH = 32
        private const val TOKEN_HEADER_NAME = "x-focuswave-token"
        private const val TOKEN_COOKIE_NAME = "FocusWave-Token"
        private const val BEARER_PREFIX = "Bearer "
        private val AUTH_CODE_PATTERN = Regex(""""code"\s*:\s*"(\d{4})"""")
        private val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001F\\u007F]")
        private val UNSAFE_FILE_NAME_CHARACTERS = Regex("""[/:*?"<>|]""")

        private val FALLBACK_HOME_PAGE = """
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

