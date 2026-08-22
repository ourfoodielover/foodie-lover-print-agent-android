# Foodie Lover Print Agent (Android)

Native Kotlin port of `print-agent/index.js`, running as a background foreground service on
the manager's Android tablet, printing to the Caysn CN811 over Bluetooth Classic SPP/RFCOMM
(confirmed working in the Phase 0 physical test).

Talks to the **existing, unmodified** Foodie Lover server: `GET /api/print-jobs`,
`PATCH /api/print-jobs/[id]`, `x-print-agent-key` auth, same `print_jobs` lifecycle.

## Build

Requires: JDK 17+, Android SDK (`platforms;android-34`, `build-tools;34.0.0`), network access
to `dl.google.com` / Google's Maven / Maven Central (for the Android Gradle Plugin, AndroidX,
and Kotlin dependencies) and `services.gradle.org` (for the Gradle wrapper distribution).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Easiest path: open this folder in **Android Studio** (Hedgehog/2023.1+) and click Run/Build —
it provisions the missing SDK components and Gradle distribution automatically.

## Project layout

- `escpos/` — ESC/POS byte commands + `TicketBuilder` (byte-for-byte port of `buildKot()` /
  `buildReceipt()` from `print-agent/index.js`).
- `bluetooth/BluetoothPrinterManager.kt` — RFCOMM connect/write/reconnect to the paired CN811.
- `network/` — `PrintJobsApi` (GET/PATCH against the existing API) + JSON models matching the
  existing `print_jobs` row / payload shape.
- `service/PrintService.kt` — the single foreground-service poll/print loop. Everything else
  (boot receiver, watchdog, UI buttons) only ever starts or stops this service; nothing else
  calls the API.
- `service/WatchdogWorker.kt` — WorkManager periodic (15 min) watchdog. Restarts the service if
  it's not running; never polls the API itself.
- `service/BootReceiver.kt` — restarts the service after a reboot, only if it was left running.
- `config/` — `AppConfig` (defaults mirroring `print-agent/.env.example`) + `SecureConfig`
  (Keystore-backed `EncryptedSharedPreferences` storage for `PRINT_AGENT_KEY` and friends).
- `ui/` — MainActivity (status screen), SettingsActivity (one-time config), DevicePickerActivity
  (pick an already-paired Bluetooth device).

## Known limitation (inherited from the existing server, not introduced by this app)

If the process dies between `PATCH { status: 'printing' }` and the follow-up
`PATCH { status: 'printed' | 'failed' }`, that job is stuck at `printing` forever —
`GET /api/print-jobs` only ever returns `queued`/`failed` rows. Recovery today is the waiter
portal's existing **Reprint KOT** button. This is true of the Windows agent today too; it is
documented, not fixed, per the instruction to preserve current server behavior.

## Station id

Every `print_jobs` row is currently inserted by the web app without an explicit `printer_id`,
so it defaults to `'default'` at the database level. `PRINTER_STATION_ID` in Settings must
stay `default` unless the server is later changed to route by station.
