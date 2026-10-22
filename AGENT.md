# AGENT.md — Developer & AI Agent Guide for ContraCam (`com.mybedy.antiradar`)

This document guides AI agents and developers working on the ContraCam repository. It defines architectural invariants, the JNI boundary contract, development rules for extending the application, and the modernization roadmap.

---

## 1. Project Mental Model & Architecture Invariant

### 1.1 What This App Is (and Is Not)
* **Not an RF Radar Detector:** ContraCam performs zero radio frequency signal processing and does not read gyroscopes or accelerometers. The only background sensor used is `TYPE_ROTATION_VECTOR` purely for compass rotation.
* **Architecture:** The application is a **GPS-database POI proximity warning system** with a custom OpenGL ES 2.0 vector map engine.
* **C++ Core vs. Java Shell:** Approximately 85% of functional logic lives inside the 9.5 MB native library (`libVestigoEngines.so`). The Java layer (`com.mybedy.antiradar`) functions strictly as an orchestration shell handling Android lifecycle, UI rendering triggers, audio playback, network synchronization, and peripheral IPC.

### 1.2 The JNI Invariant (Strict Constraint)
Because `libVestigoEngines.so` is pre-compiled for `arm64-v8a`:
* **Do NOT alter package names, class names, method signatures, or parameter order of existing `native` declarations.**
* Dynamic JNI registration (`JNI_OnLoad`) and static export symbol bindings will crash with `UnsatisfiedLinkError` if Java class paths or signatures diverge.
* In particular, the 407 native bindings across `NavigationEngine`, `RadarDetectorEngine`, `MainMap`, `NavApplication`, `downloader/WebAssetManager`, `util/Setting`, `RouteEngine`, and `location/LocationAnalyzer` must remain binary-compatible.

---

## 2. Core Data Pipeline

```
[Fused / Native GPS]
       │
       ▼
LocationAnalyzer.W() / .m()
       │
       ▼
LocationAnalyzer.nativeOnLocationUpdate(...)  ─── (The ONLY GPS Ingress)
       │
       ▼ [C++ libVestigoEngines.so: Spatial BBox, Polygon Intersect, Proximity Math]
       │
       ▼
MainServiceTrigger.k.updateLocation()        ─── (The Egress Fan-Out)
       ├── HazardState[]        (RadarDetectorEngine.nativeGetHazardsState())
       ├── SpeedometerState     (NavigationEngine.nativeGetSpeedometerState())
       ├── RadarDetectorState   (NavigationEngine.nativeGetRadarDetectorState())
       └── SeqScheme[]          (RadarDetectorEngine.nativeGetSeqSchemes())
               │
               ├── AudioEngine (voice phrases, tones, beeps)
               ├── RadarDetectorController (HUD 2-slot display)
               ├── MainService (status bar notification & overlay)
               ├── BackgroundWidget (SYSTEM_ALERT_WINDOW HUD)
               ├── WearableTransport (Wear OS)
               └── Car Launchers (Agama, AutoLauncher, EconTool)
```

### Key DTO Contracts
* **`HazardState` (`core/HazardState.java`)**: Pre-calculated hazard record from native code.
  * `dist`: Distance in meters; `-1` signals average speed calculation zone.
  * `eDirection`: State transitions (`1` = arm vote-up, `2` = commit vote-down).
  * `isSpeedControl()`: Matches camera IDs or limit range ($5 \le \text{limit} \le 185$).
* **`SpeedometerState` (`core/SpeedometerState.java`)**: Speed context, legal limits, and street name.
  * *Note bug at line 85–87:* Decompiled logic collapses limits ($5 \le i \le 185$) to `i = 5`.
* **`RadarDetectorState` (`core/RadarDetectorState.java`)**: Global detector tolerances (safe excess, legal excess, phrase IDs, alerts).

---

## 3. Directory & Component Mapping

| Subsystem | Key Files / Paths | Description |
|---|---|---|
| **Lifecycle & App** | `NavApplication.java`, `StartActivity.java` | Native init (`nativeInitVestigoCore`), permission gating, delayed task bootstrap. |
| **Foreground Service** | `service/MainService.java`, `MainServiceTrigger.java` | Sticky foreground service (ID 101, `location`), notification action handlers. |
| **Location Pipeline** | `location/LocationAnalyzer.java`, `location/d.java`, `location/e.java` | Accuracy arbitration, fused vs. platform selector, JNI ingress. |
| **HUD & UI** | `rd/RadarDetectorController.java`, `MainActivity.java`, `NavFragment.java` | Dual-slot hazard presentation, PiP mode, touch gesture dispatching. |
| **Map Engine** | `MainMap.java` | GL surface wrapper routing directly to native `GLMapRender` (OpenGL ES 2.0). |
| **Audio Engine** | `audio/AudioEngine.java`, `ToneEngine.java`, `VoiceEngine.java` | Queue management for M4A voice files and synthesized tones. |
| **Asset Download** | `downloader/WebAssetManager.java`, `WebAssetUnit.java` | Plain HTTP mirror rotation, package extraction, map checksum verification. |
| **Networking & API** | `profile/NetworkHelper.java`, `profile/OauthInstance.java`, `profile/JSONService.java` | HTTP API client (OkHttp 1.x stack), crowd-source report submissions. |
| **Preferences & Storage** | `util/Setting.java` (native KV), `defpackage/y21.java` (SharedPreferences) | User preferences, flags, and persistent states. |

### Obfuscation Rosetta (`defpackage/`)
* `y21`: Central `SharedPreferences` wrapper.
* `s1`: UI main-thread dispatcher/looper post helper.
* `sn0`: Generic observer set implementation.
* `m1`, `kb`, `dw`: Car launcher adapters (Agama, AutoLauncher, EconTool).
* `gl0`: Camera 3D/2D model asset indexer.

---

## 4. Guidelines for Adding New Modules

### 4.1 Adding a New Telemetry / Sensor Consumer
* **Do NOT inject fake GPS into `LocationAnalyzer` directly.**
* If adding external GPS (e.g., Bluetooth NMEA receiver, OBD-II speed):
  1. Wrap your source in a dedicated provider under `location/`.
  2. Normalize fixes to `android.location.Location`.
  3. Route through `LocationAnalyzer.W(Location)` to ensure native thread gating, asset-download lock checks, and replay filters remain intact.

### 4.2 Adding a New Output Target (e.g., CarPlay, Custom HUD, WebSockets)
1. Implement the `StateObserver` interface (`com.mybedy.antiradar.core.StateObserver` / `MainServiceTrigger.StateObserver`).
2. Register the observer via `MainServiceTrigger.INSTANCE.registerObserver(observer)` during startup.
3. Consume `(SpeedometerState sp, RadarDetectorState rd, HazardState[] hz, SeqScheme[] sq)` inside `updateState(...)`.
4. Ensure all heavy serialization, network streaming, or Bluetooth I/O runs on a background handler thread; never block `MainServiceTrigger.k`.

### 4.3 Adding UI Widgets or Overlay Controls
* Overlay views must support Android 12+ (API 31+) overlay limitations (`SYSTEM_ALERT_WINDOW` permission checks).
* Do not interfere with `RadarDetectorController` slot assignments (Slot 0 = nearest hazard, Slot 1 = secondary hazard).
* Any tap actions on hazards should reuse existing JNI dispatchers:
  * Block hazard: `NavigationEngine.nativeBlockHazardAtSlot(slotIndex)`
  * Clear average speed: `NavigationEngine.nativeClearMotionInfo()`

---

## 5. Development Invariants & Code Rules

1. **Thread Affinities:**
   * GPS ingress runs on location provider threads.
   * Native C++ state updates run synchronously within `nativeOnLocationUpdate()`.
   * UI components (`RadarDetectorController`, `NavFragment`) must receive state dispatches on `Looper.getMainLooper()`.
   * OpenGL surface lifecycle events (`MainMap.w()`) must detach surfaces before activity recreation or risk memory leaks.
2. **Audio Priority:**
   * Safety-critical audio alerts take precedence over general notifications.
   * `AudioEngine.INSTANCE.i(...)` must be called with valid phrase IDs populated by `RadarDetectorState.phrases`.
3. **No Canvas Rendering for Maps:**
   * Map rendering is fully native OpenGLES. Do not attempt to draw maps using Android `View` or `Canvas` hierarchies; customize through `MainMap` native hooks.

---

## 6. Modernization & Remediation Roadmap

When developing or refactoring, prioritize the following debt items:

### Priority 1: Security & Transport Modernization
* [ ] **Eliminate Hardcoded Client Secret:** The OAuth client secret (`Basic contracam:contracam-secret`) in `NetworkHelper.java` is publicly accessible. Move sensitive client operations to a backend proxy or implement PKCE for OAuth2 authentication.
* [ ] **Enforce HTTPS for Asset Mirrors:** `AppDefines$App.java` hosts 5 HTTP mirrors (`HOST_ADDRESS_ONE` through `FIVE`). Migrate map and voice asset downloads to HTTPS endpoints with SHA-256 certificate pinning.
* [ ] **Secure Credential Storage:** Stop writing plaintext credentials to `AppSecretLogin` in `y21.java`. Replace with `EncryptedSharedPreferences` or Android Keystore, and clear tokens cleanly upon logout.
* [ ] **Parameterized Query Construction:** Replace raw string concatenation in `profile/e.java` and `profile/f.java` with standard URL builders to prevent parameter injection.

### Priority 2: Framework & Architecture Upgrades
* [ ] **Network Stack Modernization:** Replace the legacy OkHttp 1.x / `AsyncTask` implementations in `profile/` and `downloader/` with modern OkHttp 4 / Retrofit / Kotlin Coroutines.
* [ ] **Fix Speedometer Sign Assignment:** Fix the decompiler glitch in `SpeedometerState.java:85-87`:
  ```java
  // Current bug:
  if (i >= 5 && i <= 185) i = 5;
  // Remediation: Correctly index or map the discrete sign limit graphic.
  ```
* [ ] **Restrict Exported Receivers:** Review receivers in `AndroidManifest.xml` (`StartServiceActivity`, `Launcher` receivers). Add permission flags (`android:permission`) to prevent unauthorized third-party execution of foreground services.
* [ ] **Server-Side IAP Verification:** Migrate `IapBillingGoogle.java` from local `RussianSpeedcamsPurchased` boolean toggling to Google Play Developer API server-side purchase token verification.