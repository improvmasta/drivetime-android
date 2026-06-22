# drivetime-android

Native Android companion for **[drivetime](https://github.com/improvmasta/drivetime)** —
the in-car telemetry source. Logs high-frequency GPS (and, in Phase B, reads the
OBD-II dongle directly) and posts it to drivetime's `/api/ingest`. Later phases add
Android Auto screens and commute alerts.

Plan & decisions: see drivetime's `NATIVE_APP.md`.

## Status — Phase A (GPS logger)
- [x] Project + CI (builds a debug APK on every push)
- [x] Settings (server URL, ingest token, fix interval)
- [x] Foreground `LocationService` streaming fixes via FusedLocationProvider
- [x] `Uploader` — batched POST to `/api/ingest` with an on-disk offline queue
- [ ] Drive auto start/stop (OBD-connected **or** activity-recognition) — wiring TBD
- [ ] Background-location permission flow polish

## Phases
- **A** GPS logger *(here)* → **B** OBD via custom ELM327 layer (RPM, load, MPG,
  voltage, DTCs) → **C** Android Auto glanceable screens → **D** commute alerts.

## Build & install
CI builds `drivetime-debug-apk` on each push (Actions → artifact). Download and
sideload to the phone. Debug APKs are installable as-is.

Local build:
```bash
gradle wrapper --gradle-version 8.7   # first time (no wrapper jar committed)
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Configure on the phone
Open the app → set **Server URL** (`https://drivetime.jupiterns.org`) and the
**ingest token** (same as the server's `DRIVETIME_TOKEN`) → Save → Start logging.

## Enabling signed release builds (next)
Generate a release keystore, add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD` as repo secrets, add a `signingConfig` to
`app/build.gradle.kts`, and switch CI to `assembleRelease`. Tracked for Phase A wrap-up.

## Tech
Kotlin · min SDK 26 · FusedLocationProvider · OkHttp · coroutines · custom ELM327
(Phase B) · `androidx.car.app` (Phase C).
