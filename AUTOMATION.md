# Automating drivetime

drivetime is built to be **driven** — your phone's Samsung Modes & Routines (or
Tasker / MacroDroid / Home Assistant) is the brain that decides when to log,
which mode to use, and how to react when state changes. The app's job is to be
a clean instrument: every meaningful action is reachable as a shortcut or an
intent, and every state change emits a broadcast you can subscribe to.

The same cheat-sheet appears in the app under **Settings → Advanced → Automation**,
so a routine author never has to leave the phone. A unit test (`AutomationHelpTest`)
holds that in-app sheet to `Control.SET_KEYS`, so the key list below can be trusted
to be complete — if a key exists, it's documented.

---

## 1 · App Shortcuts (Samsung Modes & Routines, no extra apps)

In the Samsung *Modes and Routines* editor:

> **Add action** → **Apps** → drivetime → pick the shortcut.

| Shortcut    | Effect              |
|-------------|---------------------|
| **Auto**    | Auto-detect driving via car-BT / OBD / sustained speed (default) |
| **Driving** | Force the dense tier — start logging at the driving cadence now |
| **Eco**     | Force LIGHT — sparse background-only |
| **Stop**    | Turn logging off |

The shortcuts are published from `res/xml/shortcuts.xml` via `MainActivity`'s
`android.app.shortcuts` meta-data, so they appear automatically in any launcher
or routine engine that supports App Shortcuts.

---

## 2 · Broadcast intents (Tasker / MacroDroid / RoutinePlus / HA)

| Action                                                  | Effect                                  |
|---------------------------------------------------------|------------------------------------------|
| `org.jupiterns.drivetime.action.START`                  | trackingMode = AUTO                      |
| `org.jupiterns.drivetime.action.STOP`                   | trackingMode = OFF                       |
| `org.jupiterns.drivetime.action.TOGGLE`                 | OFF ↔ AUTO                               |
| `org.jupiterns.drivetime.action.MODE_AUTO`              | trackingMode = AUTO                      |
| `org.jupiterns.drivetime.action.MODE_DRIVING`           | trackingMode = DRIVING                   |
| `org.jupiterns.drivetime.action.MODE_ECO`               | trackingMode = LIGHT                     |
| `org.jupiterns.drivetime.action.SET`                    | one-shot setting change (see §3)         |
| `org.jupiterns.drivetime.action.QUERY`                  | emit a `STATE_CHANGED` broadcast back    |
| `org.jupiterns.drivetime.action.MARK`                   | stamp a marker here, now (drive only)    |

Send either:
* via **Tasker → "Send Intent"** (target: Broadcast Receiver), or
* via **"Launch Activity"** when you need to start the foreground service from
  the background — the `ControlActivity` is invisible and finishes immediately.

START from the background may be throttled by Android 12+ FGS rules; if you're
launching from a Samsung Routine, prefer the App Shortcut (§1) since it counts
as an Activity launch and is exempt.

---

## 3 · `SET` keys

`SET` takes two extras (`key`, `value`) or a single top-level extra named after
a key. Keys + value formats:

| Key                              | Type / format                              | Notes                                  |
|----------------------------------|--------------------------------------------|----------------------------------------|
| `mode`                           | `auto` `driving` `light` `eco` `off`       | `eco` is an alias for `light`          |
| `interval_sec`                   | positive int                               | Driving / moving cadence               |
| `idle_interval_sec`              | positive int                               | Driving / red-light cadence            |
| `light_interval_sec`             | positive int                               | LIGHT-tier cadence                     |
| `upload_interval_sec`            | positive int                               | LIGHT flush cadence                    |
| `driving_upload_interval_sec`    | positive int                               | DRIVING flush cadence (~10s default)   |
| `drive_by_speed`                 | `true` `false` `1` `0` `yes` `no` `on` `off` | Speed backstop on/off               |
| `stationary_stop_min`            | 0+                                         | Auto-trip backstop minutes             |
| `auto_trip`                      | bool                                       | Legacy activity-recognition auto-trip — **off by default and best left off** (its slow-traffic car/bike guess is exactly the misclassification `DriveDetector` exists to avoid) |
| `alerts_enabled`                 | bool                                       | **Check-engine notifications** — the dongle's trouble codes. On-device; nothing is polled from a server |
| `notif_driving_only`             | bool                                       | Collapse the notification when idle    |
| `motion_onset`                   | bool                                       | Significant-motion fast-start path     |
| `onset_probe_interval_sec`       | positive int                               | Probationary GPS cadence after a wake  |
| `onset_probe_window_sec`         | positive int                               | Probation duration before LIGHT        |
| `onset_speed_mps`                | positive int                               | Doppler m/s = vehicular threshold      |
| `onset_accel_rms`                | 0+                                         | Accel on-foot threshold (×100 m/s²)    |

Unknown keys log a warning to **Activity log** and are silently ignored — your
routine can safely send a SET it's not sure the app version supports without
breaking the rest of the recipe.

The event-notification toggles (`notify_drive_complete`, `notify_gas_stop`,
`notify_digest`, `notify_tracking_health`) and the sync/backup keys
(`backup_schedule`, `server_url`, …) are **not** in `SET_KEYS` — for now they are
in-app settings only. A routine that needs them can still ship a whole settings
file (§6).

### The control token

`controlToken` (Settings → Advanced) is blank by default, and **while it is blank every verb
is open** — exactly as it has always been, so no existing recipe changes behaviour.

Once you set one, an intent arriving from **outside the app** must carry a matching `token`
extra to use `SET`, `QUERY`, `MARK`, **`STOP` or `TOGGLE`**. `START` and the `MODE_*` verbs
stay open on purpose: they cannot stop logging — the worst they do is start it or change the
sampling tier — so leaving them open keeps a routine able to *recover* tracking without the
secret, while the verbs that can silently kill it are protected.

`STOP`/`TOGGLE` were open regardless of the token until hardening 3.2. That meant any app on
the phone could stop your tracking with one broadcast and no permission — and "the app quietly
stopped logging" is this project's #1 bug class, so a token that locked the settings but left
that open was protecting the wrong thing.

Two consequences worth knowing before you set a token:

- **The built-in "Stop" App Shortcut stops working.** It is static XML (`res/xml/shortcuts.xml`)
  and cannot carry a runtime secret. Stop logging with a `STOP` intent that sends the `token`
  extra instead. The Auto / Driving / Eco shortcuts are unaffected.
- **The app itself never needs the token.** The in-app Tracking switch, the resume alarm and
  the drive detector are inside the trust boundary; only the exported intent/broadcast surface
  is gated. You can always turn tracking off by opening the app.

A rejected intent is written to the in-app **Activity log** ("rejected — bad/missing token"),
never silently dropped.

---

## 4 · `STATE_CHANGED` broadcast

drivetime emits an explicit broadcast whenever tracking state changes (mode,
tier, OBD link, service lifecycle), and on every `Control` action. Subscribe to
it from Tasker / MacroDroid / HA via an Intent Filter on the action.

* **Action:** `org.jupiterns.drivetime.event.STATE_CHANGED`
* **Extras:**

| Extra                | Type    | Notes                                                  |
|----------------------|---------|--------------------------------------------------------|
| `logging`            | boolean | Service is alive and has emitted at least one fix      |
| `tracking_mode`      | string  | `auto` `driving` `light` `off` (desired)               |
| `tier`               | string  | `DRIVING` `LIGHT` `OFF` (actual)                       |
| `reason`             | string  | `car BT` `OBD` `speed` `forced` `eco` `auto`           |
| `queue_depth`        | int     | Fixes waiting to upload                                |
| `obd_connected`      | boolean |                                                        |
| `last_fix_at`        | long ms |                                                        |
| `last_success_at`    | long ms | Last successful upload                                 |
| `last_command_source`| string  | `user` `shortcut` `routine` `watchdog` `boot` …        |
| `source`             | string  | Who triggered *this* broadcast                         |
| `ts`                 | long ms | Emission time                                          |

Routines can include their own `source` extra on `Control` actions so the
echoing `STATE_CHANGED` is filterable — e.g. ignore your own commands.

---

## 5 · Recipes

### Samsung Modes & Routines — "I'm driving"
1. **Condition:** Bluetooth → car head unit connected.
2. **Action:** Apps → drivetime → **Driving** shortcut.
3. (Optional) when the same Bluetooth disconnects → **Auto** shortcut, so
   logging stays on lightly between drives instead of stopping.

### Tasker — overnight Eco
1. Profile: Time, 22:00–06:00.
2. Task: *Send Intent* → broadcast `org.jupiterns.drivetime.action.MODE_ECO`.

### Tasker — react to drivetime state
1. Profile: *Intent Received*, action `org.jupiterns.drivetime.event.STATE_CHANGED`.
2. In the task, read `%tier`, `%queue_depth`, `%obd_connected` and act
   accordingly (e.g. notify when queue > 500 — you've been offline a while).

### Home Assistant — phone arrived home, stop logging
1. Trigger: Zone enter (home).
2. Action: *Send broadcast intent* via the HA Companion app →
   `org.jupiterns.drivetime.action.STOP` with `source=home-assistant`.

### Bash from Termux (developer debug)
```bash
am broadcast -a org.jupiterns.drivetime.action.SET \
  --es key mode --es value driving --es source termux
am broadcast -a org.jupiterns.drivetime.action.QUERY
```

---

## 6 · Settings as JSON — the routine preset format

Two different things are called "backup" in this app; this section is about the
small one.

* **Settings → Sync & Backup → Backup & Restore** is the *full-data* backup —
  every drive, tag, place, and vehicle, snapshotted to Google Drive or a folder on
  a schedule. That's the one that saves you when you lose the phone; see
  `drivetime/BACKUP.md`.
* **Settings → Advanced → Export settings** is what matters to a routine author: it
  writes every editable setting (plus credentials, the control token, and your
  paired devices) to a JSON file whose keys match the SET names in §3. Import it on
  a new phone, or keep several around as one-shot presets.

```json
{
  "schema": 1,
  "server_url": "https://drivetime.example.com",
  "username": "lindsay",
  "interval_sec": 3,
  "driving_upload_interval_sec": 10,
  "tracking_mode": "auto"
}
```

Be careful where you store the file — login + control token are exported in
plaintext.
