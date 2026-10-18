# SpeedCam

A clean-room reimplementation of a speed-camera proximity warning app for Android,
built from an independent behavioural specification rather than from the original code.

**Personal use only. See [Legal status](#legal-status) before driving with it.**

See [`../Plan.md`](../Plan.md) for the full specification, rationale and staged roadmap.

---

## What this is

Detection is GPS-database proximity: the app knows where enforcement cameras are, knows
where the vehicle is, and warns when one enters a configurable radius. It performs no
RF detection and no signal jamming.

The design reference is ContraCam 4.0.135, whose ~115,000 lines of first-party code are
about 85% native C++ (`libVestigoEngines.so`, 417 `native` declarations). That binary
cannot be recompiled, so its behaviour was reconstructed from the decompiled Java surface
and re-specified explicitly. The result is roughly 3,000–4,000 lines of Kotlin.

## Status

**Stage 4 of 9 — foreground service, verified end to end on a real device.**

| Stage | | Status |
| --- | --- | --- |
| 0 | Project skeleton, CI, lint | **done** — `./gradlew build` green, debug + release APK |
| 1 | Database schema + offline importer | **done** — verified end-to-end against live Overpass |
| 2 | Detection engine | **done** — pure engine + 42 fixture tests, plus 20 tests driving the real engine over the real 11k-camera database |
| 3 | Alert profiles and presets | **done** — policy engine, 8 presets, transport selection, 39 tests |
| 4 | Foreground service and location pipeline | **done and verified on API 36** — fused provider with fallback, watchdogs, resurrection |
| 5 | Sound, gager tone, speech, vibration | tone ladder + PCM synthesis done; no playback |
| 6 | HUD, camera list, settings | placeholder |
| 7 | Average-speed zones | import path ready; runtime computation pending |
| 8 | Scheduled database updates | **done** — `update-osm.bat`, change-detecting and self-verifying |

## Modules

| Module | Kind | Responsibility |
| --- | --- | --- |
| `app` | Android | Compose UI, activity, manifest |
| `core:geo` | JVM | Haversine, bearing, angle maths, bounding boxes |
| `core:detect` | JVM | Domain model, detection engine, alert profiles |
| `core:data` | Android | Room entities, DAOs, mappers |
| `core:media` | Android | Alert sounds, distance-pitched gager tone |
| `service` | Android | Foreground service, fix arbitration |
| `tools:import` | JVM | Offline database builder (not shipped in the APK) |

`core:geo`, `core:detect` and `tools:import` are plain JVM modules with no Android
dependency at all. That constraint is what keeps the detection logic — the safety-critical
part, and the part that was untestable in the original — under unit test.

### How the pieces connect

```
SpeedCamApp ──► CameraStore ──► SpeedCamDatabase ──► CameraDao
                                        │
                                        ▼
                            CameraQueryAdapter ──► DetectionEngine
                                     (CameraQuery)      (pure)
```

```
DetectionEngine.detect()          -> List<Hazard>     which cameras matter
        |
AlertPolicy.evaluate()            -> AlertDecision?  what to say about one
        |
media / notification layer                         how to produce it
```

`core:detect` declares the `CameraQuery` contract it needs and `core:data` supplies an
implementation, so the domain never depends on Room. `CameraStore` is the composition root —
manual constructor injection rather than a DI framework, because the graph is five objects deep
and completely static.

### What is recovered, and what is not

Three things about the original are only partly knowable, and the code says so where it
matters rather than papering over the gap:

| | Status |
| --- | --- |
| The eight preset *names* | verbatim from `quick_setting_title_0..7` |
| The eight presets' *field assignments* | **not recovered.** Each is applied by `nativeApplyQuickSetting(int, boolean)`, a single native call. The one-line subtitle per preset is the only description that survives, and `QuickSettingTest` asserts the assignments against those sentences. The numeric choices are informed guesses. |
| `beep_excess_values` semantics | array name, the Russian `превышение`, and the sibling `op_warn_excess` all say the threshold is an **excess over the posted limit**, not an absolute speed. The comparison itself is native. |
| Day/night determination | `warnTime` is stored as 0/1/2 and evaluated inside the native library. The decompile contains no sunrise or sunset calculation, so `AlertContext.isNight` is supplied by the caller rather than guessed at here. |
| What Car/Truck/Moto change | applied natively, not recoverable. Only the selection rule is implemented. |
| Day/night hours | fixed 07:00–21:00. A guess, and labelled as one: the original's decompile has no twilight calculation to port. |

### Running it

The service is wired and starts itself once location permission has been granted.

```bash
./gradlew :app:installDebug
adb shell pm grant com.speedcam.app.debug android.permission.ACCESS_BACKGROUND_LOCATION
adb shell am start -n com.speedcam.app.debug/com.speedcam.app.MainActivity
```

The ongoing notification shows the nearest hazard, GPS loss and recovery, so a drive can be
verified end to end before Stage 5 adds audio.

Starting the service from outside the app does **not** work and is not meant to:
`adb shell am start-foreground-service ...` fails with `Requires permission not exported from
uid 10055`, because on Android 14+ a `location`-typed foreground service may only be started by
the app's own uid. That is why `ServiceController` exists.

### Verified on device

Samsung SM-S916B, Android 16 / API 36.

| | Result |
| --- | --- |
| Foreground promotion | `isForeground=true types=0x00000008` — `FOREGROUND_SERVICE_TYPE_LOCATION`, required from API 34 |
| Database on device | installed to `databases/speedcam.db`, 921,600 bytes, Room opened it without complaint |
| Provider | `FusedLocationSource` attached; fused fixes at ~1 Hz, accuracy 5–24 m |
| GPS-loss watchdog | announced `GPS signal lost` exactly 30 s after the last fix, then `GPS signal recovered` when fixes resumed |
| End-to-end detection | with the capture distance set to 10 km, reported the two nearest cameras at 8,225 m and 8,237 m; the notification showed the **nearer** one |
| Backgrounding | survived `KEYCODE_HOME` with the service still foreground and fixes still arriving |
| Resurrection alarm | registered as `RTC_WAKEUP` with a 15-minute window; the receiver re-arms it without crashing |

Two defects were found by running it rather than by reading it, and both are fixed: the
notification reported the *furthest* hazard because every decision overwrote it, and a camera
with no recorded limit was announced as "limit 0 km/h", asserting a fact the database does not
contain.

**Still unverified**, because they need a device that moves:

- the background-abuse watchdog's positive case. Its negative case passed — backgrounding the
  app while stationary correctly did not trip it.
- `BOOT_COMPLETED` itself; only the alarm action was exercised.
- two hours of backgrounded running, which Plan.md §8.1 requires. Sixty seconds was not two
  hours.

**Tests.** 212 in total. 135 use synthetic fixtures; the rest exercise the seam against the
real database — 11 drive the actual engine over rows really parsed out of OpenStreetMap, 15
cover installing that database onto a device, 9 cover the persistence-to-domain mapping, and 3
pin the build script and the installer to the same asset path. 39 more cover the alert policy
and presets, including an exhaustive sweep of all 32 enable/disable combinations of the five
channel switches. Tests that need the database skip themselves when it is absent, as it is on
a fresh clone and in CI.

## Building

Requirements:

- JDK 17 (`JAVA_HOME`)
- Android SDK with platform 36 and build-tools 35 (`ANDROID_HOME` / `ANDROID_SDK_ROOT`)

This machine's toolchain, installed under `C:\cmdline-tools`:

| Tool | Path |
| --- | --- |
| JDK 17.0.20 (Temurin) | `C:\cmdline-tools\jdk17` |
| Android SDK (cmdline-tools 19.0) | `C:\cmdline-tools\android-sdk` |
| Gradle 8.13 (for regenerating the wrapper) | `C:\cmdline-tools\gradle-8.13` |

```bash
./gradlew build             # assemble + unit tests + ktlint + detekt
./gradlew :core:detect:test # detection unit tests
./gradlew :app:assembleDebug
```

Do **not** run `clean` routinely. It deletes every output that the Gradle build cache would
otherwise restore, turning a one-second incremental build into a ~30 second one. The
configuration cache is enabled and takes a green build from 7 s to 1 s when warm.

### Building the camera database

`tools:import` is a desktop JVM program, not part of the APK. It writes the SQLite file that
`core:data` opens on the device, reproducing Room's schema exactly — including the identity
hash Room validates on open, so the two cannot silently drift.

**Bulk path (preferred).** Geofabrik publishes Russia as ten federal-district extracts, around
4 GB together. Refresh them with one command:

```bat
tools\import\update-osm.bat
```

It checks which districts actually changed upstream, downloads only those, rebuilds the
database into a staging file, verifies it, and only then swaps it into place. The previous
database is kept as `russia.db.bak`.

```bat
update-osm.bat             full check and update; exits early when nothing changed
update-osm.bat --force     refetch every district, ignoring the manifest
update-osm.bat --verify    check the existing database and exit
```

| | |
| --- | --- |
| Change detection | `osm\imported.txt` records each district's upstream MD5. Only a mismatch triggers a download, so a no-op run is seconds rather than 4 GB. |
| Safety | The new database is built at a staging path and checked with `PRAGMA integrity_check`, a non-zero camera count and a schema-hash match **before** the old one is touched. Any failure leaves the working database intact. |
| Resume safety | An interrupted download records the digest it was begun against in `osm\<name>-latest.osm.pbf.partial`. A transfer is only resumed when that digest still matches upstream — Geofabrik rebuilds every extract in one daily batch, so splicing a partial of yesterday onto today's build produces a file of roughly the right size with a wrong digest. |
| Overrides | `OSM_DIR`, `DATA_DIR`, `SPEEDCAM_FILE` environment variables. Setting `SPEEDCAM_FILE` adds the community corpus to the import. |
| Exit codes | `0` success or nothing to do, `1` failure with the previous database untouched, `2` Geofabrik unreachable and nothing changed. |

Result on this machine: **11,430 cameras across 530M scanned nodes, 5.6 minutes, 0.9 MB
database**, zero network dependency at import time.

Nothing needs to be configured. Both `update-osm.bat` and `./gradlew :tools:import:run` default
to this project's own folders:

| | |
| --- | --- |
| Extracts | `osm\` — the 10 Geofabrik district files, ~4 GB |
| Database | `data\russia.db`, with `data\russia.db.bak` as the previous version |

Neither the extracts nor the database are committed; `.gitignore` excludes both. A fresh clone
runs `tools\import\update-osm.bat` once to populate them.

### Getting the database onto the device

`data\russia.db` is what `tools:import` produces, and it is not a source file in the repository.
The build stages it into the APK as `assets/databases/speedcam.db` (921,600 bytes, compressing
to ~505 KB), and `CameraDatabaseInstaller` writes it into app storage on first launch, before
Room opens anything.

This mirrors the original, which ships `assets/overview/map.dat` (3.46 MB) and copies it out on
first run via `SystemHelper.l()`. Shipping a bootstrap map is what lets the app be useful the
moment it is installed; the three orphaned strings in the original's `strings.xml` — "Download
the main map" and friends — are the fossil of the era when that download was mandatory.

Two deliberate departures from the original:

| | Original | Here |
| --- | --- | --- |
| Verification | plain byte copy; the `IOException` is swallowed as `tttt Error restoring resources`, and the shipped asset is never checksum-checked | the staging file must carry a SQLite header before it is allowed to replace anything, and failure propagates |
| Overwrite | re-copies unconditionally on a resource-version bump | an existing database is never clobbered — it holds the user's `override_` rows. Bundle upgrades go through Room migrations; refreshing from upstream is Stage 8 |

If `data\russia.db` is absent when the app module builds, `stageCameraDatabase` warns loudly
and ships without cameras rather than failing the build, because CI has no extracts. An APK
built that way starts normally and never warns about anything, so watch for that warning.

**One-off / manual paths:**

```bash
# Single district straight from Gradle
./gradlew :tools:import:run -PosmDir=osm -Poutput=central.db

# One region from Overpass — the common case, since a driver mostly needs the roads they use
./gradlew :tools:import:run --args="--output moscow.db --osm-bbox 55.4,36.9,56.1,38.3"

# Whole country, Gradle properties rather than --args (long quoted paths do not survive)
./gradlew :tools:import:run -PosmDir=osm -Poutput=data/russia.db -PspeedcamFile=rus.txt
```

Behaviour worth knowing:

- **The PBF reader is written from scratch**, protobuf wire format included, so no external
  `osmium` binary is required. It is validated against Overpass: both paths report exactly 10
  cameras for Kaliningrad.
- **Relations need two passes.** An `enforcement=average_speed` relation names its endpoints by
  id, and they are not guaranteed to precede it. Holding every node's position instead would
  cost gigabytes.
- **A partial `.osm.pbf` is still readable.** Blobs are self-delimiting, so an interrupted
  download yields its complete prefix and is reported as truncated rather than rejected.
- **Tiling.** A single Overpass query spanning a country times out, so the online path splits
  the area into 5°×10° tiles. A tile failing three attempts is skipped and reported.
- **Mirror rotation and throttling.** Public Overpass instances degrade and return 500 without
  warning. Retries advance to the next mirror, and calls are spaced 750 ms — these are donated
  resources.
- **Deduplication.** Records within 8 m collapse, keeping the richest field values. The radius
  is deliberately tight: Russian complexes put several devices on one gantry metres apart, and
  merging those would lose a real warning.
- **What the parsers refuse to guess.** A named speed zone such as `RU:urban` stays an unknown
  limit rather than resolving to a default. The SpeedCam parser never derives a facing
  direction, because a wrong one silently suppresses legitimate warnings. The raw SpeedCam
  parameter line is stored verbatim so a corrected parser can be re-applied without refetching.

### Test coverage

| Suite | Tests | What it covers |
| --- | --- | --- |
| `DetectionEngineTest` | 25 | Distance, bearing, facing gate, back-shot gate, speed gate, auto radius, antimeridian |
| `GeoTest` | 17 | Haversine, bearing, angle normalisation, bounding boxes |
| `SpeedCamParserTest` | 16 | Coordinate forms, type markers, limit recovery, encoding fallback |
| `DeduplicatorTest` | 14 | Radius merge, field selection, stability |
| `FixArbiterTest` | 11 | Accuracy arbitration, fused-provider precedence, unusable-fix rejection |
| `GagerTonesTest` | 9 | Frequency ladder, step index, PCM envelope |
| `DatabaseWriterTest` | 8 | Row round-trips, source partitioning, transactions |
| `OverpassMapperTest` | 7 | `maxspeed` / `direction` parsing |
| `BoundingBoxTest` | 6 | Tiling, bounds, degenerate boxes |
| `PbfReaderTest` | 12 | Varints, zigzag, dense-node deltas, coordinate scaling, relations, truncation |
| `PbfImporterTest` | 4 | Camera extraction, two-pass relation resolution, dangling references |
| `SchemaParityTest` | 4 | Importer DDL against Room's exported schema |
| `ImportPipelineTest` | 3 | End-to-end fetch → parse → merge → write against a local stub |
| **Total** | **135** | |

## Design notes

**Explicit over hidden.** Where the original resolved behaviour inside its native library —
notably what "Auto" capture distance meant — this implementation writes the rule down:

```
effectiveDistance = min(1500 m, 2 s × speed + 300 m)
```

**Direction matters.** A camera on the opposite carriageway photographs traffic coming at
it. Suppressing those requires both the recorded facing direction (`direction=` in OSM) and
an oncoming filter, which is what `backshotOnly` does.

**Pitch rising with proximity.** The distance-pitched gager tone is ported exactly from the
original, including its `floor(km × 16.0934)` step index. It is the most recognisable part
of the reference audio and costs very little to reproduce.

**`beepSpeedThresholdKmh` is a speed, not a distance.** Ported from the original's
`beep_excess_values`. Getting this wrong changes the app from warning about speeding to
warning about proximity.

## Attribution

Camera data derived from OpenStreetMap is licensed under the **ODbL 1.0** and requires:

> Data © OpenStreetMap contributors

The SpeedCam community corpus is **not yet cleared for use**; see `NOTICE.md` and
[`../Plan.md`](../Plan.md) §9.1.

## Legal status

Read this before using the app on public roads.

**In the Russian Federation:**

| Device | Status |
| --- | --- |
| Anti-radar (active jammer) | **Prohibited** — Art. 13.3 KoAP RF, since 2013 |
| Radar detector (passive receiver) | **Not prohibited** — confirmed by Rosavtodor (2018) and Minjust (Feb 2024) |
| GPS camera-warning app | **Grey area** — Art. 12.15 KoAP RF applies a 1,500–2,000 RUB fine with double confiscation to "use of special technical means signalling warning of speed enforcement" |

The Art. 12.15 wording does not specify that the device must be a radio device. A GPS-only
app emits nothing, and most readings would not classify it as a "special technical means",
but the literal text is broader than the common interpretation. GUBDD has publicly floated
amendments (October 2024). No Art. 12.15 enforcement has been observed in practice.

Guidance: personal use, no distribution, no advertising.

**Elsewhere:** radar detectors are prohibited in 14 of 16 surveyed European countries,
while camera-warning apps are permitted nearly everywhere — banned only in Germany and
Switzerland. Check the rule that applies where you drive.

## Disclaimer

This software is provided as-is, without warranty of any kind. A warning app is a
supplement to attention, never a substitute for it. Drive to the conditions, not to the
notifications.
