# Project Instructions

- When the user asks a question about code, do not immediately edit files.
- Before making code changes, ask whether the user wants Codex to modify the files or wants an explanation so they can code it themselves.
- Only change files after the user explicitly asks Codex to make the change.
- Keep explanations concise and practical.
- When providing code snippets or implementation guidance, always include the relevant file path.

# Project Context

Focus Wave is an Android app with two major feature areas:

- Focus timer
- Local Wi-Fi secure file sharing

The local file sharing feature is the current major development area.

# Local Wi-Fi Secure File Sharing

## Overview

The app acts as a local Wi-Fi file server. Users on the same local network can securely upload and download files between a PC web browser and the Android device without routing file data through an external internet server.

Target architecture:

- Android app UI: Java/Kotlin Android project using Gradle Kotlin DSL
- Embedded local server: Kotlin, NanoHTTPD
- Web client: HTML and JavaScript
- Browser cryptography: Noble Cryptography

## Completed Work

- Set up an embedded local HTTP server on Android using NanoHTTPD.
- Implemented a browser-based web UI for file transfer.
- Established end-to-end encryption using X25519 ECDH for key exchange.
- Uses HKDF-SHA256 to derive AES keys.
- Applies AES-CBC encryption for file data.
- Upload and download file data are processed as streams/chunks, avoiding unencrypted temporary files.
- File metadata is encrypted.
- Plain-text file name headers were removed.
- Encrypted file names are passed through custom HTTP headers:
  - `X-File-Name-Encrypted`
  - `X-Meta-Nonce`
- File name length leakage mitigation is implemented with fixed 256-byte padding before encryption, so encrypted name length stays constant.

## Prioritized Roadmap

Implement these file sharing tasks in this exact order:

1. Token-based IP management.
   - Current priority.
   - Refactor the existing IP-based connected device management into a token-based system.
2. Localhost connection fix.
   - Fix errors that occur when the web server is accessed directly from the host Android device itself.
3. Trusted device feature.
   - Add "Trust this device" using the new token system.
4. Volatile chat.
   - Add ephemeral, non-persistent local chat between connected devices.

## Later Backlog

- Fix the issue where Wi-Fi or network state changes are not immediately reflected in the app.
- Investigate migrating the web client's chunk-based AES-CBC cryptographic logic to AES-GCM.
