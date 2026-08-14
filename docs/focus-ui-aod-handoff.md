# Focus UI / AOD Mode Handoff

## AOD Background Darkness Setting

`FocusScene` now supports an `overlayDarkness` style parameter for controlling how dark the background scrim appears over the image.

Future settings behavior:

- Timer mode must keep a fixed overlay darkness of `0.5f`.
- AOD mode should use a user-controlled setting.
- The user-facing setting range should be `0` to `100`.
- Internally, map that range to `0f` to `1f`.
- Default AOD overlay darkness should be `0.5f`.

Suggested mapping:

```kotlin
val overlayDarkness = userValue.coerceIn(0, 100) / 100f
```

Do not apply the user setting to Timer mode. Timer mode should remain visually consistent regardless of the user's AOD background darkness preference.

## AOD Background Gallery

Current AOD mode uses a bundled drawable background directly, for example:

```kotlin
R.drawable.bg_son_na_eun2
```

Future behavior:

- Add an in-app gallery for AOD background images.
- Allow the user to add custom photos.
- If the user has added photos, use user photos instead of the bundled default image.
- If multiple user photos exist, choose one randomly for the AOD background.
- If no user photo exists, fall back to the current bundled default drawable.

Suggested priority:

1. User-added gallery images, randomly selected when more than one exists.
2. Bundled default AOD drawable fallback.

Keep this separate from Timer mode background handling unless explicitly requested later.

## Git Staging Note

Do not include temporary bundled AOD background experiments in commits unless explicitly requested.

Exclude these files when staging:

```text
app/src/main/res/drawable/bg_son_na_eun*.jpg
```

Example:

```bash
git add -- . ':!app/src/main/res/drawable/bg_son_na_eun*.jpg'
```

## Current Branch Handoff

Current branch during the latest UI/AOD work:

```text
feature/app-ui-redesign
```

Current base at the time of this note:

```text
e60fffd Merge pull request #13 from b6star/feature/local-chat
```

Important current working-tree context:

- `app/src/main/java/com/yourssu/focuswave/MainActivity.kt` has uncommitted AOD background rotation changes.
- Timer and AOD backgrounds were intentionally split into separate state:
  - `selectedTimerBackground`
  - `selectedAodBackground`
- `selectedBackground` is now a calculated value from `focusMode`, not its own saved state.
- AOD background should keep the previously selected AOD image when switching from Timer mode to AOD mode.
- AOD background should rotate only after the configured interval. The current default is 5 minutes.
- Screen rotation should not immediately change the selected background.
- Timer background is currently fixed to `R.drawable.bg_timer_milkyway`, but the structure intentionally uses `timerBackgroundsPath` so more Timer backgrounds can be added later.

Current drawable state to review before committing:

- Modified:
  - `app/src/main/res/drawable/category_cafe.png`
- Deleted:
  - `app/src/main/res/drawable/grok_space_01.jpg`
  - `app/src/main/res/drawable/grok_space_02.jpg`
  - `app/src/main/res/drawable/grok_space_03.jpeg`
  - `app/src/main/res/drawable/grok_space_04.jpeg`
  - `app/src/main/res/drawable/space1_bg.jpg`
- Untracked new backgrounds:
  - `app/src/main/res/drawable/bg_aod_milkyway.png`
  - `app/src/main/res/drawable/bg_aod_milyway2.png`
  - `app/src/main/res/drawable/bg_aurora.jpg`
  - `app/src/main/res/drawable/bg_timer_milkyway.png`
- Still exclude unless explicitly requested:
  - `app/src/main/res/drawable/bg_son_na_eun.jpg`
  - `app/src/main/res/drawable/bg_son_na_eun2.jpg`

Before continuing after Codex restart:

1. Run `git status --short --branch`.
2. Confirm whether the drawable deletions are intentional.
3. Continue from `feature/app-ui-redesign`, not `feature/local-chat`.
4. Re-run `./gradlew.bat assembleDebug` after any follow-up changes.
