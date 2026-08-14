# File Sharing Trusted Device Handoff

## Goal

Local Wi-Fi file sharing is being refactored from IP-based connected-device handling to token-based trusted-device handling.

## Collaboration Preference

When the user asks "다음", "next", "다음에 해야 할 것", or similar, do not provide a large multi-step plan by default.

Give only the next concrete step, explain why that step comes first, and keep it practical. The user usually prefers to write the code directly, so provide implementation guidance instead of editing files unless the user explicitly asks Codex to modify code.

## Realtime Communication Preference

From this point forward, new realtime file-sharing logic should prefer SSE over polling.

Existing polling-based logic, such as the current file-list refresh in the browser client, does not need to be rewritten immediately unless the user explicitly asks for it. For new trusted-device work and similar event-driven flows, design around SSE.

For trusted-device registration specifically, do not send the raw trusted-device token through SSE. Use SSE only as a notification channel, then let the browser call a claim endpoint so the server can set the trusted-device token as an `HttpOnly` cookie.

### NanoHTTPD SSE Implementation Note

Do not use NanoHTTPD's default `newChunkedResponse()` for SSE in this project.

During trusted-device registration, the browser's `EventSource` connection opened successfully, but no SSE messages reached the browser. Server logs showed the trusted grant was issued and the approved event was written, yet `/trusted-device/claim` was never called.

Root cause: NanoHTTPD 2.3.1's default chunked response path does not flush each chunk immediately. SSE needs each event chunk to be flushed to the socket as soon as it is written.

Current fix:

- `LocalFileServer.kt` uses a custom `SseResponse : NanoHTTPD.Response`.
- `SseResponse` writes HTTP headers and chunked SSE frames manually.
- It calls `outputStream.flush()` after every chunk.
- `SseEventStream` uses a `LinkedBlockingQueue<ByteArray>` so server code can enqueue SSE messages from app/UI callbacks.

Keep this custom response path for future SSE work unless the embedded server implementation changes.

## Screen-Off Server Stability / Power Management

Current issue: when the Android screen turns off, `LocalFileServer` may still be alive, but the PC browser can lose connection or stop receiving timely responses.

Goal:

- Keep browser connections stable even when the Android screen is off.
- Minimize battery usage while the server is idle and no file transfer is active.
- Maximize CPU/Wi-Fi availability during active upload/download transfers.
- Do not keep the screen on as the solution.

Likely cause:

- The server object/process is not necessarily dead.
- Android may put CPU and Wi-Fi into power-saving behavior when the screen is off.
- A local HTTP server can then respond late, stall, or drop browser connections.

Do not use `keepScreenOn` as the real solution. It keeps the display on and wastes battery, while the desired behavior is: screen may turn off, but local file sharing should continue.

Recommended long-term design:

1. Move `LocalFileServer` ownership from `FileServerManager`/ViewModel into a Foreground Service.
2. Keep the service running with a visible notification while file sharing is enabled.
3. Use `PARTIAL_WAKE_LOCK` and `WifiLock` only during active file transfer, not constantly.
4. Track transfer activity with an idle timeout, not a fixed total timeout.
5. Optionally add battery-optimization-exclusion guidance as a supplementary user setting.

Preferred power policy:

- Server idle mode:
  - Foreground Service remains active.
  - Avoid strong WakeLock/WifiLock if no transfer is happening.
  - Battery usage stays lower while waiting for browser requests.
- Transfer mode:
  - Acquire `PARTIAL_WAKE_LOCK`.
  - Acquire high-performance Wi-Fi lock.
  - Keep locks while data is actively flowing.
  - Release locks after transfer completion or after an inactivity timeout.

Important timeout rule:

- Do not use a fixed total timeout such as "release after 5 minutes".
- Large files, such as 20GB transfers, can legitimately take a long time.
- Use an idle timeout instead:
  - update `lastTransferActivityMillis` whenever upload/download bytes are read or written;
  - release transfer locks only if no activity occurs for a period such as 60 seconds.

Upload tracking is relatively straightforward because upload bytes are read inside `handleUpload()` / `saveUploadedFileStream()`.

Download tracking is slightly more involved because `handleDownload()` returns a response stream and NanoHTTPD performs the actual stream reads after the handler returns. To track download activity accurately, wrap the download `InputStream` so each successful `read()` updates transfer activity and `close()` schedules transfer-mode release.

Browser-side "download complete" callbacks may be added as a helpful signal, but they should not be the only signal. The browser may close, cancel, or disconnect before sending completion. Server-side stream close/activity tracking plus idle timeout is still needed.

This is a larger architectural change. Do not implement casually as a quick patch unless the user explicitly asks to start the Foreground Service migration.

The intended behavior is:

1. A new browser authenticates with the 6-digit code.
2. The Android app notices the session-only device and asks whether to trust it.
3. If approved, the app stores a trusted-device record locally.
4. The browser receives and persists a long-lived trusted-device token.
5. On later visits, the browser can prove trust with that token instead of being treated as a new untrusted device.

## Current Code State

### Database Layer

Implemented files:

- `app/src/main/java/com/yourssu/focuswave/server/FocusWaveDatabase.kt`
- `app/src/main/java/com/yourssu/focuswave/server/TrustedDeviceEntity.kt`
- `app/src/main/java/com/yourssu/focuswave/server/TrustedDeviceDao.kt`
- `app/src/main/java/com/yourssu/focuswave/server/TrustedDeviceRepository.kt`
- `app/src/main/java/com/yourssu/focuswave/server/TrustedDeviceTokens.kt`

The DB table is `trusted_devices`.

`TrustedDeviceEntity` stores:

- `id`
- `tokenHash`
- `displayName`
- `userAgent`
- `lastIpAddress`
- `trustedAtMillis`
- `lastSeenAtMillis`

The raw trusted-device token is not stored. `TrustedDeviceRepository.trustDevice()` hashes the token with `TrustedDeviceTokens.hashToken()` and stores only the hash.

`TrustedDeviceRepository.findTrustedDeviceByToken()` hashes the incoming token, looks it up by `tokenHash`, and updates `lastSeenAtMillis`, `lastIpAddress`, and `userAgent`.

### Server Auth Flow

Relevant file:

- `app/src/main/java/com/yourssu/focuswave/server/LocalFileServer.kt`

`LocalFileServer` now accepts two callbacks:

- `findTrustedDevice`
- `onUntrustedClientAuthenticated`

Current `/auth` flow:

1. Validate request content type.
2. Parse submitted auth code.
3. Reject invalid code.
4. Generate a short-lived session token.
5. Read `FocusWave-TrustedDevice` cookie if present.
6. Look up the trusted device through `findTrustedDevice`.
7. Create a `SharedSourceIdentity`.
8. If no trusted device matched, call `onUntrustedClientAuthenticated`.
9. Return the session token and set `FocusWave-Token`.

Important detail: the trusted-device cookie is checked only after the 6-digit auth code succeeds. So trusted devices do not currently bypass the auth code, even though the UI copy says they will.

### App Manager Flow

Relevant file:

- `app/src/main/java/com/yourssu/focuswave/server/FileServerManager.kt`

`FileServerManager.startServer()` passes callbacks into `LocalFileServer`.

`findTrustedDevice` currently calls the repository through `runBlocking` from the NanoHTTPD request thread.

When `LocalFileServer` reports an untrusted authenticated client, `onUntrustedClientAuthenticated()` appends the `SharedSourceIdentity` to `TrustedDeviceUiState.pendingDevices`.

Trust prompt flow:

1. `pendingDevices` receives the session-only device.
2. User taps yes.
3. `startTrustedDeviceNaming()` moves the device to `namingDevice`.
4. User confirms or skips naming.
5. `saveTrustedDevice()` generates a trusted token and stores it in DB through `trustedDeviceRepository.trustDevice()`.
6. `namingDevice` is cleared.

Critical gap: the generated trusted token is lost after saving its hash to DB. It is not passed back to `LocalFileServer`, not returned to the browser, and not set as a browser cookie.

### Compose UI

Relevant file:

- `app/src/main/java/com/yourssu/focuswave/ui/fileshare/FileShareScreen.kt`

Implemented UI:

- Trust-device prompt dialog: `TrustDevicePromptDialog`
- Naming dialog: `NameTrustedDeviceDialog`
- The overlay shows the first pending device from `trustedDeviceUiState.pendingDevices`.

The prompt copy currently says:

> 이 기기를 신뢰하면 다음 접속부터 인증 코드를 다시 입력하지 않아도 됩니다.

That behavior is not implemented yet because `/auth` still requires a valid code before trusted-device lookup.

### Web Client

Relevant file:

- `app/src/assets/index.html`

The web client still only supports manual auth-code login.

Missing pieces:

- No trusted-device status polling.
- No trusted-device cookie receive flow.
- No auto-auth flow using `FocusWave-TrustedDevice`.
- No UI state for "waiting for phone approval".

The browser sends cookies with `credentials: "same-origin"`, so once a trusted-device cookie exists, later requests can include it. The missing part is issuing and using that cookie.

## Main Problems To Fix Next

### 1. Trusted-device token never reaches the browser

`FileServerManager.saveTrustedDevice()` generates `trustedToken`, saves only the hash, then drops the raw token.

Need to bridge this token back to the browser that owns `source.sessionToken`.

Practical design:

- Add a pending trusted-token grant map in `LocalFileServer`, keyed by current session token.
- Add a method like `grantTrustedDevice(sessionToken: String, trustedToken: String)`.
- In `FileServerManager.saveTrustedDevice()`, after DB save succeeds, call `server?.grantTrustedDevice(source.sessionToken, trustedToken)`.
- Add a browser endpoint such as `GET /trusted-device/grant`.
- Browser polls this endpoint after normal auth.
- When grant exists, server returns success and sets:
  - `Set-Cookie: FocusWave-TrustedDevice=<trustedToken>; Path=/; HttpOnly; SameSite=Strict; Max-Age=...`

### 2. Trusted devices do not bypass the auth code

Current `/auth` validates the 6-digit code before reading `FocusWave-TrustedDevice`.

Need to choose the intended behavior:

- If trusted devices should skip the code, add a trusted-cookie path before code validation.
- If trusted devices should still enter the code, change UI copy because the current message is wrong.

Recommended behavior for roadmap item "Trusted device feature":

- Add a new route, for example `POST /auth/trusted`, or allow `/auth` with an empty body when a valid trusted cookie exists.
- If the trusted cookie is valid, issue a fresh short-lived `FocusWave-Token`, set `SharedSourceKind.TRUSTED_DEVICE`, create AES exchange normally, and skip the 6-digit code.
- If the cookie is missing or invalid, return 401 and show the existing auth-code form.

### 3. Web client needs a trust lifecycle

After code auth succeeds:

- Show the file-sharing UI as it does now.
- Start polling `/trusted-device/grant` for a short period.
- If the phone approves, the polling response sets `FocusWave-TrustedDevice`.
- Stop polling after success, denial, timeout, or logout/reset.

On page load:

- Try trusted auth first.
- If trusted auth succeeds, run `setupEncryption()`, hide auth form, show file-sharing UI, and start file-list refresh.
- If it fails, show the auth-code form.

### 4. Trusted-device list state is not wired

`TrustedDeviceUiState` has `trustedDevices`, and `TrustedDeviceRepository` exposes `trustedDevices`, but `FileServerManager` does not collect that flow into UI state yet.

Needed later:

- Collect repository flow in `init`.
- Show trusted devices in the app UI.
- Add delete/revoke action using `TrustedDeviceRepository.deleteTrustedDevice(id)`.

### 5. `runBlocking` in server callback is a risk

`FileServerManager.startServer()` uses `runBlocking` inside `findTrustedDevice`.

This works for a small local server, but it blocks the NanoHTTPD request thread while Room queries run. Better long-term options:

- Make trusted-device lookup synchronous with a small in-memory cache populated from Room.
- Or queue auth work onto a coroutine and return an async-style response only if NanoHTTPD usage allows it.

For now, this is acceptable as a temporary bridge, but it should not become the final architecture if more trusted-device logic is added.

## Suggested Next Implementation Order

1. Add a server-side trusted-token grant mechanism keyed by session token.
2. Call that grant mechanism from `FileServerManager.saveTrustedDevice()` after DB save.
3. Add `GET /trusted-device/grant` endpoint that requires the current session token.
4. Add browser polling after code auth and set the long-lived trusted-device cookie from that endpoint.
5. Add trusted-cookie auto-auth before the code form.
6. Update UI copy only after the skip-code path is actually implemented.
7. Wire `TrustedDeviceRepository.trustedDevices` into `TrustedDeviceUiState.trustedDevices`.
8. Add revoke UI.

## Verification Done

`./gradlew.bat assembleDebug` succeeds.

No code changes were made during this documentation pass except this handoff file.

## Sound Mixer Category Refactor Handoff

The next sound mixer change should convert the current flat sound-track model into a category/option model.

Current concept:

- Each sound item is treated as an independent track.
- This makes `RAIN_THUNDER`, `RAIN_THUNDER2`, and `RAIN_IN_CAR` playable at the same time even though they belong to the same Rain theme.

Target concept:

- `Rain`, `Ocean`, `Cafe`, `Space`, and `City` should be sound categories.
- Each category should contain multiple selectable sound options.
- The same category can have only one selected option at a time.
- Different categories can still play at the same time.
- If there is no persisted user choice, each category should use its default option.
- After the user selects an option, that option becomes the category's default selection for later use.

Recommended naming:

- Prefer `soundCategories` over `soundThemes`.
- Prefer `SoundCategoryUiState`, `SoundCategoryId`, `SoundOptionUiState`, and `SoundOptionId`.
- Avoid continuing to use `SoundTrackUiState` for categories because it makes the model harder to read.

Primary files to update:

- `app/src/main/java/com/yourssu/focuswave/ui/state/TimerUiState.kt`
  - Replace `soundTracks` with `soundCategories`.
  - Add `isSoundSelectionMode`.
  - Replace `SoundTrackUiState` / `SoundTrackId` with category and option types.
  - Define `defaultSoundCategories`.
- `app/src/main/java/com/yourssu/focuswave/ui/timer/TimerViewModel.kt`
  - Replace `setSoundEnabled` with category-based enable handling.
  - Replace `setSoundVolume` with category-based volume handling.
  - Add `toggleSoundSelectionMode()`.
  - Add `selectSoundOption(categoryId, optionId)`.
  - Update paused-sound snapshot and restore logic to use category IDs.
- `app/src/main/java/com/yourssu/focuswave/ui/components/SoundMixer.kt`
  - Change `SoundMixerPanel` to receive `soundCategories`.
  - Add a header-right selection-mode toggle button.
  - Do not add per-category sound selection buttons.
  - When selection mode is on, expand every category card and show its option list separated from the main card controls.
  - Card tap should continue to toggle category playback.
- `app/src/main/java/com/yourssu/focuswave/ui/sound/SoundPlaybackEffect.kt`
  - Change playback input from tracks to categories.
  - Create or select `MediaPlayer` by `SoundOptionId`, not category ID.
  - For each enabled category, play only the selected option.
- `app/src/main/java/com/yourssu/focuswave/MainActivity.kt`
  - Pass `timerUiState.soundCategories`.
  - Pass `timerUiState.isSoundSelectionMode`.
  - Wire the new ViewModel callbacks into `SoundMixerPanel`.
  - Pass paused playback categories into `SoundPlaybackEffect`.

Suggested UI behavior:

- Selection mode off:
  - Category card tap toggles playback on/off.
  - Volume slider controls that category.
  - The selected option name is shown as secondary text.
- Selection mode on:
  - The `SOUND MIXER` header button appears active.
  - All category cards show their sound options.
  - Selecting an option changes only that category's `selectedOptionId`.
  - Selecting an option should not automatically toggle playback unless the user explicitly decides to change that behavior.

Persistence note:

- The project does not yet have persistence for selected sound options.
- Later, store the selected `SoundOptionId` per `SoundCategoryId` with DataStore or another lightweight preference store.
- On app start, load saved selections; if missing, use each category's built-in default option.

## Low Priority Backlog: Chat Code Highlighting

Later, consider adding syntax highlighting for code blocks in local chat messages.

Target behavior:

- Plain chat messages should keep the current simple bubble UI.
- Only fenced code blocks should be highlighted, for example:

````text
```kotlin
private const val MAX_MESSAGES = 300

private fun sendMessage() {}
```
````

- Kotlin code should visually distinguish keywords such as `private`, `const`, `val`, and `fun`.
- Function names should use a separate accent color.
- This is low priority. Do not block chat, file sharing, trusted-device work, or AOD work on this.

Possible implementation options:

- Lightweight local implementation:
  - Add `app/src/main/java/com/yourssu/focuswave/ui/chat/CodeHighlighter.kt`.
  - Convert Kotlin code blocks into `AnnotatedString`.
  - Render code with `FontFamily.Monospace` in `ChatScreen.kt`.
- Library-based implementation:
  - Search for `Jetpack Compose syntax highlighting`, `Compose Markdown syntax highlighting`, or `Kotlin Compose syntax highlighter`.
  - Markdown-based rendering is probably the best fit if chat messages support fenced code blocks.

Relevant files:

- `app/src/main/java/com/yourssu/focuswave/ui/chat/ChatScreen.kt`
- `app/src/main/java/com/yourssu/focuswave/ui/state/ChatUiState.kt`
- `app/src/assets/index.html`
