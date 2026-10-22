# ContraCam (`com.mybedy.antiradar`) — Code Analysis

Static analysis of the decompiled XAPK in this directory. Source tree: `sources/` (JADX output, 9,103 `.java` files), manifest at `resources/com.mybedy.antiradar.apk/AndroidManifest.xml`, native lib at `resources/config.arm64_v8a.apk/lib/arm64-v8a/libVestigoEngines.so`.

| | |
|---|---|
| Package | `com.mybedy.antiradar` |
| Version | `4.0.135-Google` (versionCode `40135`) |
| SDK | min 32 / target 36, compile 36 |
| ABI | arm64-v8a only |
| Native | 1 library: `libVestigoEngines.so`, 9,953,728 B |
| App code | 359 `.java` files under `com/mybedy/antiradar/` |

---

## 1. The one thing to understand first

**ContraCam is not a radar detector.** Despite the name, there is zero RF signal
processing. There is no accelerometer, no magnetometer, no gyroscope anywhere in
app code. The only sensor used is `TYPE_ROTATION_VECTOR` (`location/f.java:27`),
and only to rotate the map compass while backgrounded.

Detection is **GPS-database POI proximity**: the app knows where every speed camera
in the downloaded map is, knows where you are, and warns you when a camera enters
a configurable radius around your position.

More importantly: **almost none of the logic is in the Java layer.** Java is a
view/audio/IO shell. The map renderer, the spatial index, the hazard proximity
maths, routing, the geocoder, and asset management are all in a single 9.5 MB C++
library. The Java class names are misleading — `RadarDetectorEngine` is a file of
121 `native` declarations and nothing else.

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  libVestigoEngines.so  (C++, 9.5 MB)                         │
│                                                              │
│  NavigationProcessor ──┬── MapRouteEngine    (routing)       │
│                         ├── RadarDetectorEngine (hazards)    │
│                         ├── LiveObjectEngine  (crowd data)   │
│                         ├── GeocoderEngine    (addr/region)  │
│                         ├── DataSource        (SQLite spatial)│
│                         ├── MapDataTree/Level/Layer (OSM)    │
│                         ├── GLMapRender + GLES* (OpenGL ES)  │
│                         ├── ImageManager, EditorEngine       │
│                         └── Setting (native key/value store) │
└──────────────▲───────────────────────────────────┬───────────┘
               │ 221 + 121 + 30 + ... JNI calls   │
┌──────────────┴───────────────────────────────────▼───────────┐
│  Java layer (com.mybedy.antiradar)                          │
│                                                              │
│  NavApplication ──── owns engine lifecycle                   │
│  MainServiceTrigger ─ owns the per-fix data pipeline         │
│  LocationAnalyzer ── the ONLY GPS ingress                    │
│                                                              │
│  UI:  MainActivity → NavFragment → MainMap → GL SurfaceView   │
│       RadarDetectorController (the HUD)                      │
│  Audio: AudioEngine → SoundEngine / ToneEngine / VoiceEngine │
│  Net:  AppProfile → JSONService → OauthInstance → NetworkHelper│
│  Data: WebAssetManager → WebAssetDownload/WebAssetUnit        │
└──────────────────────────────────────────────────────────────┘
```

### The JNI boundary — 407 native declarations

| Class | `static native` | Role |
|---|---|---|
| `NavigationEngine` | 221 | map camera, POI CRUD, track recorder, ~60 setting getters/setters |
| `RadarDetectorEngine` | 121 | hazard profiles, categories, features, votes, corrections |
| `MainMap` | 30 | GL renderer lifecycle, touch, zoom, 2D/3D toggle |
| `NavApplication` | 8 | core init, platform prep, observers |
| `downloader/WebAssetManager` | 13 | asset checksum, version, unpack |
| `util/Setting` | 8 | native key/value persistence |
| `RouteEngine` | 6 | route state, external route handoff |
| `location/LocationAnalyzer` | 3 | `nativeOnLocationUpdate`, `nativeOnHeadingUpdate`, `nativeOnLocationError` |

Loaded once in a static initializer — `NavApplication.java:160`:

```java
static {
    try { System.loadLibrary("VestigoEngines"); }
    catch (UnsatisfiedLinkError e) {
        if (!e.getMessage().contains("VestigoEngines")) throw e;
        System.exit(0);              // wrong ABI → hard exit, not degrade
    }
}
```

The `.so` uses `JNI_OnLoad` dynamic registration for the big engine surfaces, plus
statically-named exports for `Setting`, `VoiceEngine` and `WebAssetManager`.

---

## 3. Startup sequence

```
NavApplication.onCreate  (NavApplication.java:457,461)
  ├─ initNativePlatform()                        :433
  │    ├─ createPlatformDirectories(...)
  │    ├─ nativePreparePlatform(apkPath, filesDir)   :435
  │    ├─ AppBackgroundStateObserver.j(...)
  │    └─ MainServiceTrigger.m()      ← foreground/background observer
  └─ initNativeCore(isHotUpdate)                 :362
       ├─ nativeInitVestigoCore(langCode, memClassBucket, isHotUpdate)  :385
       ├─ nativeSetWritableFolder(storagePath)
       ├─ initNativeStrings()      ← ~30 localized strings pushed to native
       ├─ AudioEngine.f(this)
       ├─ thread "AssetManagerInitThread" → WebAssetManager + AudioEngine
       ├─ LocationAnalyzer.C(); WebAssetManager.u0()
       ├─ nativeSubscribeOn{MapUpdate,MapEndUpdate,CompassUpdate,HazardCaptured}Observer
       ├─ nativeLoadCollections()
       ├─ +5s   nativeDeferredProcedures()
       └─ +120s AudioEngine.u(5)
```

`StartActivity` is the launcher (`AndroidManifest.xml:169-179`). It does permission
gating, then a three-stage delayed handoff to `MainActivity`
(`StartActivity.java:136-167`):

```
onCreate   → initView() + schedule 3 Runnables
onResume   → PermHelper.l()  → dismiss dialogs, run initCoreDelayedTask
init()     → initResources(...) → initPlatformAndCore(true) → resumeDialogs()
resumeDialogs()  → WelcomeFragment / OnBoardingFragment / startMainActivity()
```

Note `StartActivity` also routes to `MapPlaceholderActivity` immediately if the
device is an Android Auto head unit (`StartActivity.java:239-242`).

---

## 4. The detection pipeline (the actual core)

### 4.1 GPS ingress — a single chokepoint

```
FusedLocationProvider / AndroidNativeProvider
  → LocationAnalyzer.W(loc) / .m(loc)
  → LocationAnalyzer.N()                              :372
      ├─ for each LocationObserver:  updateLocation()
      └─ for each UpdateStateObserver: updateState()
  → MainServiceTrigger.k.updateLocation(Location)     MainServiceTrigger.java:67
```

Provider choice — `LocationAnalyzer.java:343-345`: Google fused if
`GoogleApiAvailability == SUCCESS && Setting.h0()`, else the platform provider.
Quality arbitration compares accuracy against `max(5, avgSpeed) * elapsed`
(`location/d.java:33-35`); a `"fused"` fix always wins (`location/e.java:16-21`).

### 4.2 Into native

`LocationAnalyzer.java:87` — the only place GPS enters the detector:

```java
LocationAnalyzer.nativeOnLocationUpdate(
    location.getTime(), location.getLatitude(), location.getLongitude(),
    location.getAccuracy(), location.getAltitude(),
    location.getSpeed(), location.getBearer());
```

Gated while a web asset download is in flight (`!WebAssetManager.INSTANCE.T()`,
`:86`) and while the replay flag is set (`:83`).

### 4.3 Out of native

`MainServiceTrigger.k.updateLocation` (foreground path, `:70-101`) fans out:

```java
HazardState[]        hz = RadarDetectorEngine.nativeGetHazardsState();
SpeedometerState     sp = NavigationEngine.nativeGetSpeedometerState();
RadarDetectorState   rd = NavigationEngine.nativeGetRadarDetectorState();
SeqScheme[]          sq = nativeIsActiveSeqs() ? nativeGetSeqSchemes() : null;

for (StateObserver o : this.i) o.a(sp, rd, hz, sq);
AudioEngine.INSTANCE.i(hz, sp, rd.phrases, rd.getSoundId());   // voice/beeps
MainService.v(sp, nonHiddenHz, ...);                            // overlay
J(sp, list) → BackgroundWidget.w0(...);                         // HUD widget
K(sp, list) → WearableTransport.p(...);                         // Wear OS
y(hz);                                                          // vote FSM
AppState.INSTANCE.j(sp, rd, hz);                               // UI observers
m1.o(...); kb.o(...); dw.o(...);                                // car launchers
```

### 4.4 What native actually does

From mangled symbols in the `.so`:

```
RadarDetectorEngine::Analyze(MapPoint, DrivenContext, vector<IntRecordPoint>)
RadarDetectorEngine::BuildCandidates(...)      → vector<MapHazard>
RadarDetectorEngine::ProcessHazards(...)
RadarDetectorEngine::AssignHazards(...)
RadarDetectorEngine::UpdateHazards(...)
RadarDetectorEngine::UpdateHazardsStates()
```

Fed by spatial queries:

```
DataSource::GetPOIsByBBox(MapBoundBox)
DataSource::GetSpeedCamerasByBBox(MapBoundBox)
DataSource::CreateSpatialIndexes()
MapDataLevel::LoadBboxRegion(MapBoundBox, bool)
MapDataLayer::LoadPOIStream(...)
MapBoundBox::IntersectBoundBox(...)
```

Geometry:

```
cross_rectangles(...)          rectangle / radar-cone intersection
ClosestPointToSegment(...)
Bearing(MapPoint, MapPoint)    ← compass direction to hazard
DistanceInMeters(...)
MapHazard::ContainsSector / ContainsRectangle / InitDistance
MapHazard::UpdateState / IsNeedToTell / DistanceSpeech / TypeSpeech
```

So: load the tiles around you → spatial query → intersect with the detector
polygon → compute distance + bearing → decide whether to warn → pick a voice
phrase. **`dist`, `dir`, `eDirection`, `veracity` all arrive pre-computed from
native.** Java only marshals, renders and plays audio.

### 4.5 The three state DTOs

**`core/HazardState.java`** — one detected hazard, fields populated by the native
constructor (`:669-726`):

| Field | Meaning |
|---|---|
| `dist` | distance in metres; `-1` ⇒ show "average speed" instead |
| `dir` | bearing to hazard |
| `eDirection` | `1` = arm a vote-up, `2` = commit a vote-down |
| `type` / `subType` | `ERecordType` + subtype (camera, post, roadworks…) |
| `modelId` | camera model → which icon to draw |
| `veracity` | star rating; `-1` = hide stars |
| `phrases` | `IntVoicePhrase[]` built natively |
| `speed` / `limit` / `avgSpeed` / `deltaSpeed` | km/h |
| `firstAppearance`, `muted`, `hidden`, `canVote`, `vibro`, `beep` | alert config |

`isSpeedControl()` (`:819-825`) = `HazardSign.isCameraSign()` (hazardId in 5..185)
or a limit in 5..185. `isDangerous()` (`:778-817`) spans ~35 hazard ordinals.

Nested `HazardSign` (`:196-659`) is a 67-entry constant table that also owns all
drawing logic — background shapes per category, fill/stroke colours, night-mode
switching via `NavigationEngine.nativeIsNight()` (`:412,435`).

**`core/SpeedometerState.java`** — road speed-limit context: current speed,
average speed, legal limit, city/street name, avg-speed restriction window.
Nested enum `SpeedometerSign`: `MAX_SPEED_SIGN_BECOME_NARROW` (-65),
`NO_OVERTAKING` (-64), `NON` (0), `MAX_SPEED` (5).

> There is a decompilation-visible clamp at `SpeedometerState.java:85-87`:
> `if (i >= 5 && i <= 185) i = 5;` — every real speed limit collapses to one sign
> entry. Almost certainly `i = 5` was meant to be a lookup, not an assignment.
> Cosmetic, but it means per-limit sign art is not being selected.

**`core/RadarDetectorState.java`** — engine-wide detector config: speed,
soundId, vibro, legalExcess, legalSafeExcess, phrases, message.

### 4.6 The HUD

`rd/RadarDetectorController.java` (1,336 lines) — `rd` is **Radar Detector**, not
"road detection". Single entry point:

```java
public void Y(List hazards, SpeedometerState sp, RadarDetectorState rd) {  // :1245
    if (d0(sp, hazards)) return;   // PiP mode takes over
    e0(rd);                        // toast
    c0(hazards);                   // 2 hazard slots
    g0(NavigationEngine.nativeGetTrackState());
    f0(sp);                        // speed + street sign
    x();                           // layout / colours / route shift
}
```

Behaviour worth knowing:
- **Two slots** (near + far) — `c0()` at `:889-929`.
- **Tapping a sign blocks the hazard** — `nativeBlockHazardAtSlot(0|1)` at
  `:402`/`:457`.
- **Double-tap within 2000 ms resets average speed** — `nativeClearMotionInfo()`
  at `:251-257`.
- **Excess colouring** — `P()`/`Q()` at `:582-624` map speed-vs-limit to
  `FINE_EXCESS` (red) / `SAFE_EXCESS` (yellow) / `AVERAGE`.
- Camera-model popup only when `firstAppearance` and the profile's model setting
  is `LARGE`/`SMALL_AND_LARGE` (`:872-880`).

---

## 5. Service layer

`service/MainService.java:48` — a `foregroundServiceType="location"` service,
notification id **101**, `START_STICKY`.

```
StartServiceActivity → MainServiceTrigger.A(ctx)
                          └─ ContextCompat.startForegroundService(MainService)
StopServiceActivity  → MainServiceTrigger.E(ctx)
                          └─ MainService.s()  → unbind, stopForeground, stopSelf, exit
```

`MainService.r(boolean)` (`:278-322`) is the UI-driven start: `synchronized` on a
static lock, permission check on `ACCESS_FINE_LOCATION`, then `bindService` with
`BIND_AUTO_CREATE | BIND_IMPORTANT`, then a `startForegroundService` posted to
the *front* of the looper queue (a race workaround).

### The notification

`MainService.e(boolean)` (`:137-195`) — channel `channel_com_mybedy_antriadar`,
importance **LOW**, silent, title/body both the app name, big custom `RemoteViews`
on API 31+. On Android Auto the close button is hidden.

Four `BroadcastReceiver` handlers (manifest `:293-296`, no intent-filter, invoked
only via `PendingIntent.getBroadcast`):

| Receiver | Action | Effect |
|---|---|---|
| `NotificationCloseButtonHandler` | `close` | stop service + exit app |
| `NotificationAddStationaryCameraButtonHandler` | `addStationaryCamera` | `nativeAddCameraInMotion(1)` + upload |
| `NotificationAddMobileCameraButtonHandler` | `addMobileCamera` | `nativeAddLiveObjectInMotion(232)` + upload |
| `NotificationAddMobilePostButtonHandler` | `addMobilePost` | `nativeAddLiveObjectInMotion(233)` + upload |

The last three are the **one-tap crowd-sourced report** feature — the whole point
of the app's social layer. `MainService.c(int)` at `:111-122` refuses if
`getDir() < 0` (no bearing ⇒ cannot place), otherwise uploads via `AppProfile`.

### `MainServiceTrigger` — the orchestrator

- `o()` (`:302-333`) — lazily init core, init renderer, register the
  `LocationAnalyzer` observer, apply the warning profile.
- `k` (`:65-146`) — the core `LocationObserver`, i.e. §4.3 above.
- `y(HazardState[])` (`:407-425`) — **two-tap voting FSM** on the background
  widget. Keys hazards by `((long)(lon*1e6) << 32) | (long)(lat*1e6)`;
  `eDirection==1` arms, `eDirection==2` commits.

### Auto start/stop receivers

All preference-gated (`StartOn*` / `StopOn*` in SharedPreferences):

| Receiver | Trigger | Extra behaviour |
|---|---|---|
| `BootCompletedReceiver` | `BOOT_COMPLETED` / `QUICKBOOT_POWERON` / `REBOOT` | honours `StartOnBootEnabled` |
| `PowerConnectionReceiver` | charger connect/disconnect | distinguishes USB / AC |
| `DockConnectionReceiver` | `ACTION_DOCK_EVENT` | starts on `DOCK_STATE_CAR` (AAOS) |
| `GPSConnectionReceiver` | `PROVIDERS_CHANGED` | `exported="false"` |
| `BluetoothConnectionReceiver` | ACL connected/disconnected | **also auto-switches warning profile** |
| `SensorRestarterBroadcastReceiver` | `restartAction` | see below |

`BluetoothConnectionReceiver.d(prefs,name)` (`:39-58`) matches the connected
device name against `warningProfile{Common,Car,Truck,Moto}Bluetooth` and calls
`nativeSetWarningProfile(...)`. Plug in your car headset → the app silently
switches to the truck profile.

**`SensorRestarterBroadcastReceiver` is misnamed — it has nothing to do with
sensors.** It is the classic keep-alive pattern:

```java
// MainService.onDestroy  :398-404
if (e) {                    // e == true only if the app started the service
    Intent i = new Intent(AppDefines$ACTION.RESTART_ACTION);
    i.setPackage(getPackageName());
    i.setClassName(getPackageName(), SensorRestarterBroadcastReceiver.class.getName());
    sendBroadcast(i);
}
```

`e` is set true in `r()` and false in `s()`, so a **user-initiated stop does not
restart** — only an OS kill does.

---

## 6. UI layer

### The map is custom OpenGL ES, not Google/Yandex

`NavFragment.java:60` owns `MainMap map = new MainMap(ou.Device)`, which wraps a
`SurfaceView`. `MainMap` has 30 natives — `nativeCreateRender`, `nativeAttachSurface`,
`nativeOnTouch`, `nativeOnPinchTouch`, `nativeSmoothProc`, `nativeHeadingProc`,
`nativeToggle2d3d`, `nativeOnLongTap` → `CapturedObject`. **Rendering never
touches Android `Canvas`.**

The manifest declares `glEsVersion="0x20000"` **required** (`:40-42`). The `.so`
contains `GLMapRender`, `GLESITex2d`, `GLESTexLoader`, `GLMapPolyline`,
`GLMapBuilding`, `GLMapPOI`, `GLMapRoute`, `GLMapText`, … — a full 2D/3D vector map
renderer over an OpenStreetMap-derived data model.

Touch is hand-rolled (`NavFragment.onTouch` `:447-600`): 1000 ms long-press,
300 ms double-tap window, >50 px/s distinguishes drag from tap, single-tap
deferred 300 ms so double-tap can cancel it, >250 px/s starts a 13 ms inertia
timer. Three independent render timers: 66 ms smooth-move, 10 ms heading, 13 ms
velocity.

### The `<queries>` block is handoff-only

The manifest lists 21 navigation apps (`:58-79`). `defpackage/s91` matches them
1:1 in the same order and does **launch handoff only** — ContraCam never requests
a route from them and never consumes their map data. The block exists purely so
`getApplicationInfo()` succeeds under Android 11+ package-visibility filtering.

`s91.a(Activity, String)` (`:30-59`) does split-screen launch: `setLaunchBounds`
bottom half + reflective `setLaunchWindowingMode(3)`.

### `MainActivity`

`MainActivity.java:103` — 87 KB, implements 8 interfaces. Layout `R.layout.act_map`
with `mirrored_layout`, `map_fragment_container`, `map_controls`, `fade_view`,
`menu_frame`, `fragment_container`, `radar_detector_frame`.

- **PiP**: `enterPipMode()` (`:577-612`), 5 aspect ratios from `Setting.C()`;
  `supportsPictureInPicture="true"` in manifest.
- **Back → PiP**: two redundant paths, `onBackPressed()` (`:1365`) and an
  `OnBackPressedDispatcher` callback (`:1896`), both call `enterPip()`.
- **Config changes**: `onConfigurationChanged` (`:1476-1494`) calls `recreate()`;
  the `recreate()` override (`:1846`) first destroys the GL context
  (`mapFragment.destroyContext()` → `MainMap.w()` → `nativeDetachSurface(true)`)
  or it leaks.
- **Intent queue**: 4 `Serializable MapTask` types (`:308-310`) that survive
  process death, drained only when `isMapRendererActive()`.

---

## 7. Data layer

### Asset download — 5 hosts, all plain HTTP

`AppDefines$App.java:11-15`:

```java
HOST_ADDRESS_ONE   = "http://contracam.ru"
HOST_ADDRESS_TWO   = "http://contracam.org"
HOST_ADDRESS_THREE = "http://mybedy.website"
HOST_ADDRESS_FOUR  = "http://contracam.website"
HOST_ADDRESS_FIVE  = "http://contracam.online"
```

`WebAssetManager.O(country)` (`:518-602`) builds a country-conditioned,
**randomly seeded** host ordering (Ukraine fixed; KZ/UZ/TJ/TM/KG and everyone else
`Random.nextInt(5)`). On any failure `v0()` (`:1363`) rotates round-robin:

```java
int size = (this.f + 1) % this.c.size();
this.f = size;
this.e = (String) this.c.get(size);
```

Flow:

```
1. GET <host>/v6.0/prod/maplst.txt          HttpURLConnection, 60s timeout
2. parse: size/V1/A1/E1/VVVV/FFFF/country/region[/type]
3. URL: <host>/v6.0/prod/<Country>/<Region>/map.dat          (WebAssetDownload.f :169)
       <host>/v6.0/prod/Models/<Country>/<Region>/package.vpk   (:173)
       <host>/v6.0/prod/Voices/<Country>/<Region>/package.vpk   (:177)
4. DownloadManager.enqueue OR URL.openStream()  AsyncTask fallback
5. nativeVerifyImageChecksum(path)           WebAssetUnit :758
   nativeGetMapFormatVersion() + major-version gate       :772-783
6. nativeAddImage() | nativeUnpackPackage()
```

Voice (`v7.0`) and models (`v1.0`) have their own format versions; the manifest
gate rejects a pack whose format major exceeds client support.

### API — separate host, HTTPS, country-split

`OauthInstance.Z0()` (`:341-348`):

```java
return nativeGetRecentCountry().equalsIgnoreCase("Russia")
     ? "https://spb.main.vps.mybedy.beget.tech"
     : "https://api.mybedy.com";
```

The five `HOST_ADDRESS_*` constants are **not** used for the API — they are the
asset CDN only.

Stack is **OkHttp 1.x** (`com.squareup.okhttp`, not okhttp3), built in
`OauthInstance.Y0()` (`:1414-1419`) with 240 s connect/read/write timeouts and
nothing else. Roughly 80 endpoints across `/oauth/token`, `/registration`,
`/user/*`, `/live/*`, `/review/*`, `/chat/*`, `/user/collections*`, `/mobile/*`,
`/references/*`.

On HTTP 401 with `error_description` starting `"Invalid access token"`,
`NetworkHelper.a2()` (`:1072-1091`) forces a full re-sign-in.

### Auth

`profile/JSONService.java:68-81` declares three backends — `FIREBASE`,
`COGNITO`, `OAUTH2` — but only `OAUTH2` is instantiated
(`AppProfile.java:2320`). The other two are dead leftovers from the sibling
"uTruck" product.

Tokens live in SharedPreferences via `defpackage/y21`:

| Key | `y21` | Meaning |
|---|---|---|
| `AppUsernameLogin` / `AppSecretLogin` | `:17`, `:37`, `:304` | **plaintext password** |
| `acct` | `:268`, `:512` | OAuth access token |
| `reft` | `:205`, `:484` | OAuth refresh token |
| `ttla` | — | absolute expiry (epoch ms) |
| `RussianSpeedcamsPurchased` | `:336`, `:356` | premium flag |

Sign-out (`y21.a()`) clears `ttla`, `AppUsername`, `AppSecret`, `userName`,
`userId` — **but not `AppSecretLogin`**, so the plaintext password survives.

### Telemetry

No Firebase Analytics, no `FirebaseMessaging`, no datatransport call sites in app
code. Telemetry is first-party: `GET /mobile/state` drives
`POST /mobile/settingsGeneral` with ~90 fields (`NetworkHelper.u1()` `:2070-2145`),
plus `y1()` navigation profile (`:2346-2399`) and `A1()` per-profile radar detector
settings (`:241-278`). Includes `serviceBluetoothDevices[]` — the paired Bluetooth
device set — uploaded wholesale at `:2127-2132`.

### Billing — local entitlement only

`util/billing/IapBillingGoogle.java` uses `BillingClient`. Product
`com.mybedy.antiradar.russiaspeedcams`, `NON_CONSUMABLE`.

On `onPurchaseSuccess`:

```
PrefIABFragment.w()  :303
  → i8.b() → SystemHelper.G0() → y21.h1()
  ⇒ SharedPreferences "RussianSpeedcamsPurchased" = true
  and (fire-and-forget) AppProfile.x1() → POST /user/payments
```

There is **no** `verifySignature()`, **no** Play Developer API call, **no**
server-side receipt validation, and **no** server-side entitlement gate. Both
callbacks of the `/user/payments` upload are empty no-ops
(`AppProfile.java:2706-2718`). Entitlement is a single locally-writable boolean.

### Storage

No SQLite in app code at all — `SQLiteOpenHelper` / `ContentValues` / `rawQuery`
have zero hits under `com/mybedy/antiradar/**`. The spatial DB is native
(`DataSource::CreateMapPointTable`, `CreateSpatialIndexes`). Java persistence is
SharedPreferences + flat files:

```
getFilesDir()/maplst.txt, maplst_temp.txt
getFilesDir()/ContraCam/<Country>/<Region>/package.vpk
getFilesDir()/ContraCam/<Country>/<Region>/<IntVoicePhrase>.m4a
<writableMapFolder>/<Country>/<Region>/map.dat
```

`storage/StorageManager.java` relocates the map folder across volumes when
internal storage runs short — `nativeRemoveAllImages()`, copy, rollback on
`IOException`, then `nativeSetWritableFolder(newPath)` + re-index.

---

## 8. Integration surfaces

| Surface | Entry | Notes |
|---|---|---|
| **Car launchers** | `integration/launcher/Launcher.java` | 3 CIS launchers (Agama, AutoLauncher MotorCar, EconTool). All `exported="true"`, pull-based: launcher broadcasts a command int, app pushes state back to the latched package with `setPackage()`. Pushed extras include `camera_type` (25-arm remap), `speed_limit`, `distance` (converted to **feet**, × 1609.34). |
| **Android Auto** | `car/CarAppService.java` | Category-`POI` map template, not turn-by-turn. `HostValidator` uses the **stock sample allowlist**. `onCreate` blocks on `CountDownLatch` until map assets unpack. |
| **Wear OS** | `widget/wear/WearableMessageService` | Paths `/start_up_service`, `/stop_service`, `/wearable_foreground`, `/wearable_background`. |
| **Home widgets** | 4 × `AppWidget` | `AppWidget` (service toggle + 7-variant broadcast), `AppWidgetStatistics`, `AppWidgetTransportProfiles`, `AppWidgetLaunchModes`. |
| **Background overlay** | `background/widget/BackgroundWidget.java` (1,560 lines) | `SYSTEM_ALERT_WINDOW` floating HUD with 7 draggable buttons; uses reflection on `WindowManager$LayoutParams` field names to survive OEM ROM renames. |
| **Lock screen** | `service/OnLockedScreenActivity.java` | Translucent, `turnScreenOn`, `requestDismissKeyguard`. Two overlay implementations: `StaticNotifications` and the animated `TransformNotifications` (1,590 lines), selected by `Setting.h()`. |

---

## 9. Security observations

These are static-analysis findings on a commercial app, listed because they are
architecturally significant. Nothing here was executed or tested.

### 9.1 Hardcoded OAuth2 client secret

`NetworkHelper.java` builds `Authorization: Basic base64(contracam:contracam-secret)`
at **16 call sites**, including:

```java
L498: /oauth/token           (the token endpoint itself)
L703: /registration
L562: /registration/resetPassword
L336: /user/roadObjectStateByUser
L457: /live/likeObjectById
L720: /live/reviewCorrections
L917: /live/dislikeObjectById
L1477:/live/voteObjectsByIds
L1940:/live/getBBoxesV2
L2009:/live/getObjectsV2
L1180:/user/pictureById
L2043:/references/modelsByTypeAndRegion
L2067:/user/objectPictureIds
L2167:/mobile/state
```

A resource-owner client secret in a distributed APK is extractable by anyone.
It authenticates **unauthenticated** operations — registration, password reset,
anonymous camera submission, votes — as the app's own service principal. This is
the single highest-impact finding.

### 9.2 Plaintext password in SharedPreferences

`AppProfileFragment.java:296` writes the raw `EditText` password via
`y21.C0(user.toLowerCase(), rawPassword)` → prefs `AppSecretLogin`
(`y21.java:37`). It is read back on every silent re-auth
(`AppProfile.java:1926` → `OauthInstance.java:2110`) and is **not cleared on
sign-out**. No hashing of any kind — no `MessageDigest`, PBKDF2, bcrypt, scrypt
or Argon2 in app code. Password change sends the new value as a raw `text/plain`
body to `PATCH /user/password`.

### 9.3 Map/voice/model delivery over plain HTTP

All five asset CDN hosts are `http://`. The entire safety-critical database —
where every speed camera is — plus all voice packs are delivered with no
transport encryption, no certificate pinning anywhere in the app, and silent
round-robin failover across five hosts.

Integrity is `nativeVerifyImageChecksum` / `nativeVerifyPackageChecksum` — a
self-contained file checksum computed over the downloaded blob. There is no
server-signed manifest, no signature over the checksum, and no expected digest
delivered over an authenticated channel. Whether this is exploitable depends
entirely on what the native routine actually does; a plain CRC/SHA over the file
rather than a signature check would make this a content-substitution primitive.

### 9.4 Internal endpoints shipped in production

- `profile/d.java:47` — `http://35.171.64.48:8082/utruckwebservices-0.0.2-SNAPSHOT/`
  (raw AWS IP, cleartext, non-standard port). Dead code — OAUTH2 is the only
  instantiated backend — but it leaks internal infrastructure.
- `profile/CognitoInstance.java:82` — `https://app.utruck.eu/utruckwebservices-0.0.2-SNAPSHOT/`.
- `widget/app/AppWidgetStatistics.java:42,60` —
  `http://contracamrest-env.eba-cq8sjpvp.us-east-2.elasticbeanstalk.com/mobile/regionalStat`.
  This one is **live** — a hardcoded `-env` Elastic Beanstalk endpoint over
  plain HTTP, authenticated with the same shared Basic pair.

### 9.5 Premium is a writable local flag

See §7. Single `SharedPreferences` boolean, no receipt validation, no server
entitlement. On a rooted device it is editable.

### 9.6 No URL encoding in hand-rolled query/form builders

`profile/f.java:22` and `profile/e.java:35` build query strings and
`application/x-www-form-urlencoded` bodies by raw concatenation. Free-text
`complaint` values (`NetworkHelper.N0` `:724`, `O0` `:740`) and usernames
(`:757`) are interpolated directly, including into the `/oauth/token` request
body (`G0` `:498`).

### 9.7 Exported components

`AppWidget`, `AppWidgetStatistics`, `AppWidgetTransportProfiles`,
`AppWidgetLaunchModes`, `StartActivity`, `MainActivity`, `WidgetActivity`,
`WidgetStatisticsActivity`, `StartServiceActivity`, `StopServiceActivity`,
`CarAppService`, `WearableMessageService`, and the 3 launcher receivers plus
Bluetooth/Power/Dock receivers are all `exported="true"`. Several are driven only
by intent extras — e.g. `StartServiceActivity` starts a foreground location
service with no caller validation.

---

## 10. Reading guide

| If you want to understand… | Start here |
|---|---|
| how a warning is produced | `MainServiceTrigger.java:67` → `RadarDetectorEngine` natives → `.so` `RadarDetectorEngine::Analyze` |
| how the map renders | `NavFragment.java` → `MainMap.java` → `MainMap.nativeCreateRender` |
| how assets arrive | `WebAssetManager.java:518` (host order), `:997` (manifest parse), `WebAssetUnit.java:758` (verify) |
| how auth works | `AppProfileFragment.java:296` → `OauthInstance.b0()` → `NetworkHelper.G0()` |
| where settings live | `util/Setting.java` (8 natives → native key/value store) + `defpackage/y21.java` (SharedPreferences) |
| what the HUD shows | `rd/RadarDetectorController.java:1245` |
| all endpoints | `profile/NetworkHelper.java` (~80 `Request.Builder()` sites) |
| obfuscated helpers | `defpackage/` — `y21`=prefs, `s1`=main-thread post, `sn0`=observer set, `m1`/`kb`/`dw`=launcher adapters, `gl0`=model assets |

Obfuscation note: R8 with `-r8-map-id-23ca21f2...`. Core classes keep names
(`NavigationEngine`, `MainService`, `HazardState`) but methods are `a/b/c/...`.
Leaf helpers were moved wholesale into `defpackage/` with meaningless names. The
`.so` retains unmangled C++ class names, which is often the faster oracle.

---

## 11. Summary

ContraCam is a well-engineered but heavily native-dependent app. The Java layer
is a thin shell: lifecycle, permission gating, view binding, audio playback, and
HTTP marshalling. Everything that makes the product work — the vector map
renderer, the spatial index, hazard proximity maths, routing, geocoding, asset
verification — lives in one 9.5 MB C++ library loaded in a static initializer that
`System.exit(0)`s on a wrong ABI. Reading the Java alone gives you roughly 15% of
the behaviour; the rest requires `.so` symbol analysis.

The data flow is clean and unidirectional: GPS in via a single JNI chokepoint
(`LocationAnalyzer.java:87`), hazard/speedometer state out via three natives,
fanned out to audio, HUD, overlay, Wear, car launchers, AAOS and widgets. The
architectural weak points are all at the edges rather than the core: a hardcoded
OAuth2 client secret gating unauthenticated endpoints, a plaintext password in
SharedPreferences that survives sign-out, and the entire map/voice database
delivered over plain HTTP with only a self-contained checksum to verify it.