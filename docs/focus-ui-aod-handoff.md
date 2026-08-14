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
