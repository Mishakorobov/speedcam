# ContraCam — Areas for Development

## 1. Overview

**ContraCam** (`com.mybedy.antiradar`, v`4.0.135-Google`, versionCode `40135`) is an Android speed-camera
proximity warning app. Despite the name it performs **no RF signal processing** — detection is purely
*GPS-database POI proximity*: it knows where every speed camera in the downloaded map is, knows where you
are, and warns you when one enters a configurable radius. It renders its own vector map with a custom
**OpenGL ES 2.0** engine and runs as a sticky foreground service with a floating HUD overlay, Wear OS,
Android Auto and car-launcher integrations.

Critically, this working directory is **not an application source tree — it is a JADX decompile dump of
the shipped XAPK** (`sources/` = 9,103 decompiled `.java` files, `resources/` = manifest + res + dex,
plus the 46 MB binary `app.apk`). There is **no build system, no version control, no tests and no CI**
anywhere in the directory. Approximately 85% of product logic lives in a pre-compiled 9.5 MB native
library (`libVestigoEngines.so`, arm64-v8a only) behind ~400 `native` method declarations, which the
project's own `AGENT.md` §1.2 declares a **strict binary-compatibility constraint**.

This has a direct consequence for everything below: most findings are *not* "bugs introduced by a
developer" but **debt inherited from a compiled, obfuscated (R8, map-id `23ca21f2…`) artefact**. The
right framing for each item is "what needs to change in the real upstream project", and several items
are genuinely unfixable from this tree alone. That distinction is flagged per finding.

---

## 2. Tech stack

| Layer | Detail |
|---|---|
| Language | Java (app), plus Kotlin stdlib/coroutines and `kotlinx.serialization` present on the classpath |
| UI | Android Views + `Fragment`/`Activity`, custom `SurfaceView` OpenGL ES 2.0 renderer |
| Native | `libVestigoEngines.so` — C++, ~9.5 MB, **arm64-v8a only** |
| SDK | `minSdkVersion 32`, `targetSdkVersion 36`, `compileSdkVersion 36` |
| Network | **OkHttp 1.x** (`com.squareup.okhttp`, pre-`okhttp3`), `HttpURLConnection`, `DownloadManager` |
| Serialization | `org.json` (`JSONObject`), `kotlinx.serialization` |
| Auth | OAuth2 resource-owner password grant, Basic client credentials |
| Persistence | `SharedPreferences` + flat files (no SQLite in app code; the spatial DB is native) |
| Billing | Play `BillingClient`, `NON_CONSUMABLE` |
| Integrations | Wear OS, Android Auto (`CarAppService`), 4 `AppWidget`s, `SYSTEM_ALERT_WINDOW` overlay, 3 CIS car launchers |
| Localization | 122 `values-*` resource dirs |
| Build / CI / Tests | **none present** |

### Repository layout

```
D:\contracam\
├── app.apk / app.xapk        46 MB each — byte-identical (SHA-256 verified)
├── AGENT.md                  agent/dev guide (dup of agent_guidelines_for_opencode.md)
├── agent_guidelines_for_opencode.md
├── code.md                   prior static analysis (697 lines)
├── resources\
│   ├── com.mybedy.antiradar.apk\   AndroidManifest.xml, res\, assets\, classes*.dex, META-INF\
│   ├── config.arm64_v8a.apk\       lib/arm64-v8a/libVestigoEngines.so
│   ├── config.mdpi.apk\
│   └── res\values*\                 122 locale/config dirs only (no layout/drawable here)
├── sources\
│   ├── com\mybedy\antiradar\        359 app-code .java files
│   ├── defpackage\                  833 obfuscated R8 leaf classes (incl. helpers y21, s1, sn0)
│   ├── androidx\ kotlin\ kotlinx\ android\ okhttp…  ~7,900 vendored/bundled library sources
│   └── _COROUTINE\ javax\ okio\ org\ nl\
└── .opencode\                      agent tooling, not part of the app
```

---

## 3. Findings

Severity legend: **H** = ship-blocker / security / data-loss · **M** = significant · **L** = cleanup ·
**INFO** = context.

---

### 3.1 Build, Repository & Tooling

#### [H-1] There is no way to build, test, lint or ship from this tree
**Where:** whole repository. Verified absent: `build.gradle`, `settings.gradle`, `gradlew`, `pom.xml`,
`Makefile`, `CMakeLists.txt`, `.git/`, any `src/`, `app/`, `androidTest/`, or CI config.
The only `.gitignore` is `D:\contracam\.opencode\.gitignore` (agent tooling).

**Why it matters:** this is the single largest constraint. Every fix below must be landed in the real
upstream Gradle project; nothing can be validated here. It also means there is **zero regression
safety net** — and the app is safety-critical (it tells a driver when to brake).

**Action:** treat `D:\contracam` as a reference/analysis workspace only. Any remediation work must be
done in the upstream repository, with the `libVestigoEngines.so` JNI contract (§ `AGENT.md` 1.2) frozen.

#### [M-2] `app.apk` and `app.xapk` are byte-identical duplicates
**Where:** `D:\contracam\app.apk`, `D:\contracam\app.xapk` — both 46,072,072 bytes, SHA-256
`4C0EDB24…93E30A62`. The `.xapk` is in fact a raw APK, not an XAPK container.

**Why it matters:** 46 MB of dead weight, and a misleading filename that invites tooling to treat it as
a bundle. The `config.arm64_v8a.apk` / `config.mdpi.apk` split directories plus the presence of
`assets/` suggest the real deliverable *is* a split/XAPK bundle — so this is also a sign the split-APK
structure was flattened incorrectly somewhere in the tooling.

**Action:** delete one; if a real XAPK is needed, rebuild it from `manifest.json`
(`resources/manifest.json`) and keep only that.

#### [L-3] Generated and vendored code is mixed into the source tree
**Where:** `sources/com/mybedy/antiradar/R.java` (1,132,637 bytes) and ~7,900 files under
`sources/androidx`, `sources/kotlin`, `sources/kotlinx`, `sources/okio`, `sources/org`,
`sources/_COROUTINE`, `sources/javax`.

**Why it matters:** first-party code is 359 files out of 9,103 — under 4%. Any search, grep or metric
run over `sources/` is ~96% noise, and there is no boundary preventing edits to library code.

**Action:** if this tree is kept, add a `.gitignore`-equivalent split (`app/` vs `vendored/`) and
exclude `R.java`.

---

### 3.2 Security

#### [H-4] Hardcoded OAuth2 client secret in a distributed binary — 17 call sites
**Where:** `Base64("contracam:contracam-secret")` built by `NetworkHelper.L1()`
(`sources/com/mybedy/antiradar/profile/NetworkHelper.java:694-696`) and passed at **16 sites** in
`NetworkHelper.java` (lines 336, 457, 498, 562, 577, 581, 703, 720, 917, 1181, 1477, 1940, 2009, 2043,
2067, 2167) plus **1** in
`sources/com/mybedy/antiradar/widget/app/AppWidgetStatistics.java:60`.

The endpoints it gates include the unauthenticated ones: `/oauth/token` (:498, :581),
`/registration` (:703), `/registration/resetPassword` (:562), anonymous camera submission
(`/live/*`), votes (`/live/voteObjectsByIds` :1477), and `/mobile/state` (:2167).

**Why it matters:** a resource-owner client secret in a shipped APK is public by definition. This
secret lets *anyone* act as the app's own service principal against registration and password-reset,
and pollute the crowd-sourced database with forged reports — the app's core social feature.

**Action:** move all unauthenticated operations behind a backend proxy that holds the secret;
adopt **PKCE** for the user-auth flows so no client secret ships at all.

#### [H-5] Plaintext password persisted in `SharedPreferences`, and survives sign-out
**Where:** `sources/com/mybedy/antiradar/profile/AppProfileFragment.java:287,296` reads the raw
`EditText` and calls `y21.C0(username.toLowerCase(), rawPassword)` →
`sources/defpackage/y21.java:36-38` writes prefs keys `AppUsernameLogin` / `AppSecretLogin`.
Read back for silent re-auth at `profile/OauthInstance.java:2110` (`b0(y21.A(), y21.f(), …)`).
No hashing of any kind anywhere in app code (no `MessageDigest`, PBKDF2, bcrypt, scrypt or Argon2).

**Why it matters:** the user's *actual account password* is recoverable from the device with
`adb`/root access, from a forensic image, or from a malicious app exploiting another bug. A stolen
password is valid against any other service where the user reused it.

**Action:** store only the OAuth `refresh_token` (rotatable, revocable, scoped). Never persist the
password. If re-auth from scratch is required, force the user through the sign-in UI.

#### [H-6] Access token **and** refresh token survive sign-out
**Where:** `sources/defpackage/y21.java:240-245` — the sign-out routine `y21.a()`:

```java
public static void a() {
    j1(-1L);                                                                    // ttla = -1
    NavApplication.prefs().edit().remove("AppUsername").remove("AppSecret").apply();
    r1(null);   // userName = null
    q1(0L);     // userId   = 0
}
```

It clears `ttla`, `AppUsername`, `AppSecret`, `userName`, `userId` — but **not**:
| Key | Written at | Meaning |
|---|---|---|
| `AppSecretLogin` | `y21.java:37` | **plaintext password** (see [H-5]) |
| `AppUsernameLogin` | `y21.java:17` | account username |
| `acct` | `y21.java:512` | OAuth **access token** |
| `reft` | `y21.java:205` | OAuth **refresh token** |

**Why it matters:** to be precise about scope — sign-out *is* functionally effective: `ttla` is set to
`-1`, and `OauthInstance.java:2088` treats `jW == -1` as "signed out" and returns early. So the app
will not silently re-authenticate. But four credential-bearing rows remain resident on disk
indefinitely. On a shared/family car head-unit, a resold device, or a forensic image, the refresh token
is still server-side valid until it expires or is revoked. **Sign-out should revoke, not just mask.**

**Action:** `y21.a()` must `remove()` all four keys. Additionally call a
`POST /oauth/revoke` (RFC 7009) for `reft` during sign-out.

#### [H-7] The safety-critical map/voice database is delivered over plain HTTP
**Where:** `sources/com/mybedy/antiradar/AppDefines$App.java:11-15` — all five asset-CDN hosts are
`http://` (`contracam.ru`, `contracam.org`, `mybedy.website`, `contracam.website`,
`contracam.online`). Cleartext is **explicitly whitelisted** for them in
`resources/com.mybedy.antiradar.apk/res/xml/network_security_config.xml:3-20`.

The only integrity gate is `WebAssetManager.nativeVerifyImageChecksum(path)`
(`sources/com/mybedy/antiradar/downloader/WebAssetUnit.java:758`) plus a format-major-version check
(`:772-783`).

**Why it matters:** this is the data the entire product is made of — where every speed camera is.
It arrives unencrypted, unauthenticated, and host selection is round-robin across five domains
(`WebAssetManager.v0()`, `WebAssetManager.java:1363-1370`). An on-path attacker can serve a modified
map. Whether that is exploitable depends entirely on what `nativeVerifyImageChecksum` does internally;
if it is a self-contained CRC/SHA over the blob rather than a comparison against an expected digest
delivered over an authenticated channel, this is a content-substitution primitive: **an attacker who
removes your speed cameras makes the app silent.**

> **Uncertainty:** the checksum routine is native and not present in this tree. I cannot confirm or
> deny exploitability. This must be resolved by auditing `libVestigoEngines.so`.

**Action (in priority order):**
1. Serve all assets over HTTPS and remove the `cleartextTrafficPermitted` domains from
   `network_security_config.xml`.
2. Publish a **signed manifest** — server signs the expected digest, client verifies the signature
   with an embedded public key before `nativeAddImage()` / `nativeUnpackPackage()`.
3. Only then consider cert pinning (pinning alone does not help while the data is unsigned).

#### [H-8] 18 exported components, none protected by a permission
**Where:** `resources/com.mybedy.antiradar.apk/AndroidManifest.xml` — `android:exported="true"` at
lines 118, 141, 151, 161, 173, 183, 196, 201, 269, 277, 320, 329, 338, 355, 363, 371, 384, 405.
**Not one** declares `android:permission`.

The two that matter most — `StartServiceActivity` (manifest:193-197) and `StopServiceActivity`
(198-202) — are `exported="true"`, `android:theme="@android:style/Theme.NoDisplay"`,
`autoRemoveFromRecents="true"`, with **no intent-filter and no permission**. `StartServiceActivity.java:15`
calls `MainServiceTrigger.A(this)` → starts a foreground location service; and `StartServiceActivity.java:18-32`
reads `getIntent().getAction()` and will **switch the user's warning profile** to
`common`/`CarContext.CAR_SERVICE`/`truck`/`moto`.

`MainActivity` (manifest:180-188) is also exported with `launchMode="singleTask"` and no permission.

**Why it matters:** any app on the device can silently start a persistent GPS-tracking service, stop
the user's existing one, or switch their warning profile — no user prompt, no permission grant. On a
head-unit (the app's primary platform) this is a realistic persistent-hijack surface.

**Action:** set `android:exported="false"` on `StartServiceActivity`, `StopServiceActivity` and
`MainActivity` (they have no intent-filters, so nothing legitimate breaks). Define a
`protectionLevel="signature"` permission for the launcher receivers. Audit each remaining exported
component individually; the 4 `AppWidget` receivers must stay exported for the framework to work, but
their action handling should ignore unrecognized senders.

#### [M-9] Exported receivers let a third party app start the service *and* change the user's profile
**Where:** manifest:317-342 — `BluetoothConnectionReceiver` (:317-325), `PowerConnectionReceiver`
(:326-334) and `DockConnectionReceiver` (:335-342) are all `exported="true"`, unprotected, with
system-action intent-filters.

`sources/com/mybedy/antiradar/service/BluetoothConnectionReceiver.java:39-58`:

```java
if (string.equalsIgnoreCase(str))      { MainServiceTrigger.u(0); return; }   // Common
if (string2.equalsIgnoreCase(str))     { MainServiceTrigger.u(1); }           // Car
else if (string3.equalsIgnoreCase(str)){ MainServiceTrigger.u(2); }           // Truck
else if (string4.equalsIgnoreCase(str)){ MainServiceTrigger.u(3); }           // Moto
```

**Why it matters:** a malicious app broadcasts `android.bluetooth.device.action.ACL_CONNECTED` with a
`DEVICE` extra naming the user's truck headset and the app **silently switches to the truck warning
profile** — altering warning distances/tolerances while they drive. The same broadcast can trigger
auto-start if `StartOnBluetooth` is on (`BluetoothConnectionReceiver.java:65`). The ACL broadcast is a
`protected` system broadcast in practice, but `PowerConnectionReceiver` / `DockConnectionReceiver` are
reachable via `sendBroadcast` with an explicit component name, which bypasses protected-broadcast
checks.

**Action:** set `android:exported="false"` on all three and rely on manifest-declared receivers
(which still receive system broadcasts). Validate the acting package for the Bluetooth ACL extras.

#### [M-10] Hardcoded Elastic Beanstalk `-env` endpoint over plain HTTP — and it is already blocked at runtime
**Where:** `sources/com/mybedy/antiradar/widget/app/AppWidgetStatistics.java:60`

```java
…new Request.Builder()
  .url("http://contracamrest-env.eba-cq8sjpvp.us-east-2.elasticbeanstalk.com/mobile/regionalStat")
  .post(RequestBody.create(JSONService.c, …))
  .header(HttpHeaders.AUTHORIZATION, …d("contracam","contracam-secret"))
```

This host is **not** in the `network_security_config.xml` cleartext allowlist, and `base-config`
(`:21-25`) sets no `cleartextTrafficPermitted`.

**Why it matters — two findings in one:**
1. **Security:** a production dev/staging environment name (`-env`) and internal infrastructure are
   shipped to users, authenticated with the shared secret from [H-4].
2. **Functional bug:** with `targetSdkVersion 36`, cleartext defaults to blocked, so this call
   **throws `UnknownServiceException`/`SSLException` on every launch** on API 28+. The
   `AppWidgetStatistics` regional-statistics feature is almost certainly dead and nobody noticed,
   because the failure is swallowed (`:57-59` `onFailure` → `this.val$listener.onUploadFail()`).

**Action:** point at the production HTTPS endpoint (`OauthInstance.Z0()`) or delete the feature.

#### [M-11] Dead auth backend ships an internal AWS endpoint
**Where:** `sources/com/mybedy/antiradar/profile/d.java:47`

```java
return "http://35.171.64.48:8082/utruckwebservices-0.0.2-SNAPSHOT/";
```

`d` is the `FIREBASE` branch of `JSONService.AuthServiceType` (`JSONService.java:68-72, 76-77`).
Verified unreachable: the **only** `new JSONService(...)` in the whole app is
`profile/AppProfile.java:2320` → `JSONService.AuthServiceType.OAUTH2`. `profile/CognitoInstance.java`
(the `COGNITO` branch) is likewise only referenced from `JSONService.java:79`.

**Why it matters:** a raw AWS IP, cleartext port, and an internal artifact path
(`utruckwebservices-0.0.2-SNAPSHOT` — from the sibling *uTruck* product) are shipped to every user.

**Action:** delete `profile/d.java`, `profile/CognitoInstance.java`, and the `FIREBASE`/`COGNITO`
enum constants. Roughly 33 KB of dead code plus the information leak.

#### [M-12] No URL encoding in hand-rolled query-string and form builders
**Where:** `sources/com/mybedy/antiradar/profile/f.java:22-26` (query) and
`sources/com/mybedy/antiradar/profile/e.java:28-40` (`application/x-www-form-urlencoded` body).

```java
// f.java — b()
str = str.equalsIgnoreCase("") ? "?" + entry.getKey() + "=" + entry.getValue()
                               : str + "&" + entry.getKey() + "=" + entry.getValue();
```

Both use `java.util.HashMap` (unordered) and concatenate `key`/`value` raw. Free-text `complaint`
values (`NetworkHelper.java:724`, `:740`) and `userName` (`:757`, `:956`) go in unencoded, and the
same `e` builder produces the `/oauth/token` body (`NetworkHelper.java:498`).

**Why it matters:** a username or complaint containing `&`, `=`, `+` or `%` silently corrupts the
request — parameter injection and broken sign-in for legitimate users with such characters. The
`HashMap` ordering also makes query strings non-deterministic, defeating HTTP caching and making
server-side logs unreproducible.

**Action:** replace with `okhttp3.HttpUrl.Builder.addQueryParameter()` /
`FormBody.Builder.add()`, which encode correctly and preserve insertion order. Migrate off
`com.squareup.okhttp` at the same time (see [M-14]).

#### [H-13] Premium entitlement is a single user-writable boolean
**Where:** `sources/com/mybedy/antiradar/util/billing/internal/google/IapBillingGoogle.java` — verified:
**no** `verifySignature()`, **no** `Purchase.getPurchaseToken()` forwarded to a server, **no** Google
Play Developer API call, **no** receipt validation, **no** server-side entitlement gate anywhere in the
file. `onPurchaseSuccess` writes a local prefs boolean (`RussianSpeedcamsPurchased`) and fires a
fire-and-forget `POST /user/payments` whose both callbacks are empty no-ops.

**Why it matters:** on a rooted device (or via a `SharedPreferences`-write primitive), setting one
boolean unlocks the paid feature. Revenue is trivially bypassable.

**Action:** verify the purchase signature locally (`Purchase.getSignature()` + Play's public key)
*and* validate the purchase token server-side via the Play Developer API. Gate the feature on the
**server** entitlement, treating the local flag as a cache only.

#### [L-14] Assorted minor hardening
| Issue | Where |
|---|---|
| `Base64.encodeToString(str.getBytes(), 2)` uses the platform default charset | `NetworkHelper.java:695` |
| Privacy policy / TOS linked over plain `http://` (GDPR/consumer-law exposure) | `AppDefines$Url.java:29-34` |
| `setJavaScriptEnabled(true)` on WebViews with no further hardening | `widget/delegate/DelegateWebContainer.java:45-47` — currently only loads `file:///android_asset/*.html`, so **low risk today**; keep it that way or disable JS |

---

### 3.3 Reliability & Concurrency

#### [H-15] Observer fan-out is not exception-safe — one throw permanently bricks the detection pipeline
**Where:** `sources/defpackage/sn0.java` — the generic observer set used by
`MainServiceTrigger.i` (`service/MainServiceTrigger.java:87, 116`) and `LocationAnalyzer.t` / `.u`
(`location/LocationAnalyzer.java:60`, iterated at `:365-369`, `:376-385`).

```java
public final class sn0 implements Iterable {
    private final Set a = new LinkedHashSet();   // not thread-safe
    private boolean d;                          // "iterating" flag

    public Iterator iterator() {
        if (this.d) { qi.a("finishIterate() must be called before new iteration"); return null; }
        this.d = true;
        return this.a.iterator();                // live iterator, no copy
    }
    public void a() { … this.d = false; }        // finishIterate()
}
```

and `sources/defpackage/qi.java:6-8`:

```java
public static void a(String str) { throw new RuntimeException(str); }
```

`finishIterate()` is called **after** the loop body, with **no `try/finally`**, at
`LocationAnalyzer.N()` lines 380 and 385, `MainServiceTrigger.k.updateLocation` lines 91 and 120.

**Why it matters — this is the most serious reliability defect found:**
1. **Permanent crash loop.** `LocationAnalyzer.N()` iterates `this.t` (line 376-379) and one of the
   observers invoked in that loop is `MainServiceTrigger.k`, which fans out to the HUD, the audio
   engine, the overlay, the widget, Wear OS and three car launchers. If **any** of those throws, the
   loop aborts, `t.a()` never runs, `d` stays `true` forever. The **next GPS fix** calls
   `iterator()` → `qi.a(...)` → `throw new RuntimeException(...)`. Every subsequent fix crashes.
   **The safety-warning feature is dead for the remainder of the process, with no way to recover short
   of killing the app.**
2. **Unbounded live iterator.** `iterator()` returns the raw `LinkedHashSet` iterator with no
   `CopyOnWrite` snapshot. A nested `iterator()` call returns `null` → immediate `NullPointerException`
   at the `for` site.
3. **Data race.** Registration (`LocationAnalyzer.o()` → `t.b()`, `:596-601`) is dispatched on the
   **main** thread via `s1.c()` (`defpackage/s1.java:19-25`, which posts to `Looper.getMainLooper()`),
   while iteration happens on the **location-callback** thread. Mutating a `LinkedHashSet` during
   iteration → `ConcurrentModificationException`, and a silent observer loss if the mutation lands
   mid-`hash` rehash.

**Action (highest-value single fix in this document):**
- Wrap both `sn0` and the iteration sites in `try { … } finally { finishIterate(); }`.
- Make `sn0` hold a `CopyOnWriteArrayList` and return a snapshot iterator; delete the manual `d` flag
  and the `qi.a()` crash-on-reentry entirely — the flag is guarding against a programming error with
  a catastrophic failure mode and no diagnostic value.
- Isolate each observer invocation in its own `try/catch` so one bad consumer cannot take down the rest.

#### [H-16] Map-database relocation deletes before it copies — unrecoverable data loss
**Where:** `sources/com/mybedy/antiradar/storage/StorageManager.java:75-123`

```
:80   WebAssetManager.nativeRemoveAllImages();   ← ALL map images destroyed
:82-99  build the source/destination file lists
:100-112 a.a(src, dst) for each file             ← copy
:113-120 s1.c(...) → nativeSetWritableFolder(...)
:121  a.l(file, fileArr);                        ← originals deleted
```

The `catch (IOException)` path at `:107-111` rolls back the *partial copies*. But there is **no
journal, no resume, and no recovery for process death or power loss** between line 80 and line 121.

**Why it matters:** the trigger is low internal storage. A user whose phone is low on space, whose
storage relocates to an SD card, and whose process is killed by the low-memory killer mid-migration
comes back to an **empty map database** — i.e. no speed-camera warnings at all, with no in-app
explanation. The only diagnostic is `e.printStackTrace()` (`:108`) and a bare `return 2`.

**Action:** reorder to *copy → verify → switch native folder → delete originals*. Add a resumable
manifest so an interrupted migration restarts rather than restarts-from-nothing. Surface a real error
to the user instead of `return 2`.

#### [M-17] Wrong-ABI failure kills the process silently
**Where:** `sources/com/mybedy/antiradar/NavApplication.java:158-167`

```java
static {
    try { System.loadLibrary("VestigoEngines"); }
    catch (UnsatisfiedLinkError e) {
        if (!e.getMessage().contains("VestigoEngines")) throw e;
        System.exit(0);        // ← wrong ABI → hard exit
    }
}
```

**Why it matters:** on an x86/x86_64 device (emulator, Chromebook, some Chromebooks-in-Android-for-Work
setups) the app vanishes on launch with no message, no crash report, and no Play Store rejection
signal. `libVestigoEngines.so` is **arm64-v8a only** (verified: only
`resources/config.arm64_v8a.apk/lib/arm64-v8a/`). The ABI filter in the manifest is the correct
mitigation and is presumably why this is rarely hit — but the code path is a black hole for
diagnostics.

**Action:** replace `System.exit(0)` with a fatal-exception path that records the reason and shows a
user-facing "unsupported device" message.

#### [M-18] 42 silently swallowed exceptions
**Where:** `catch (… ignored) { }` / `catch (… unused) { }` in app code. Worst offenders:

| File | Count |
|---|---|
| `service/notifications/TransformNotifications.java` | 9 (lines 291, 421, 483, 932, 1123, 1257, 1407, 1618) |
| `background/widget/BackgroundWidget.java` | 10 (lines 317, 428, 437, 446, 552, 1108, 1112, 1270, 1388) |
| `MainActivity.java` | 5 (lines 180, 609, 1079, 1142, 1318) |
| `service/notifications/StaticNotifications.java` | 3 |
| `preference/rd/PrefRadarDetectorFragment.java` | 4 |

Plus **6** `printStackTrace()` (`ExitActivity.java:79`, `PrefUserDataFragment.java:590`,
`util/sheet/ActionSheet.java:90`, `util/swipe/OnSwipeTouchListener.java:42`,
`widget/card/CardPageBehaviour.java:25`, `widget/pager/SlowViewPager.java:39`) and **6**
`System.out.println`.

**Why it matters:** combined with [M-10] and [M-22] this is *why* the dead statistics endpoint and the
stale `AsyncTask` went unnoticed. Critical startup and lifecycle paths (`StartActivity.java:88, 288, 304`;
`MainActivity.onCreate`) swallow failures with zero signal on a release build.

**Action:** route exceptions to a real reporting sink (see [M-26]); keep the catch blocks but log at
`WARN`/`ERROR` with the exception attached.

#### [L-19] Pin-app-widget `PendingIntent` targets a non-receiver
**Where:** `widget/app/AppWidgetStatistics.java:164` (and `AppWidget.java:32` similarly):

```java
PendingIntent.getBroadcast(context, 0, new Intent(context, (Class<?>) AppWidgetManager.class), 201326592)
```

`AppWidgetManager` is not a `BroadcastReceiver`; the explicit component can never be delivered. The
`IllegalStateException` it may throw is swallowed at `:165`.

**Action:** pass `null` as the `Intent` (the documented way to request pinning).

---

### 3.4 Performance

#### [H-20] A new `OkHttpClient` is constructed for every one of 87 requests
**Where:** `sources/com/mybedy/antiradar/profile/OauthInstance.java:1414-1421`

```java
OkHttpClient Y0() {
    OkHttpClient okHttpClient = new OkHttpClient();
    TimeUnit timeUnit = TimeUnit.SECONDS;
    okHttpClient.setConnectTimeout(240L, timeUnit);
    okHttpClient.setReadTimeout(240L, timeUnit);
    okHttpClient.setWriteTimeout(240L, timeUnit);
    return okHttpClient;
}
```

Called at **87 sites**. The stack is OkHttp **1.x** (`com.squareup.okhttp`, not `okhttp3`).

**Why it matters:** each `new OkHttpClient()` allocates its own `ConnectionPool` and `Dispatcher`
(executor). Consequences: **zero connection reuse**, a **full TLS handshake per request**, and a
fresh thread pool per request. With a **240-second** read timeout, one hung server pins a thread for
4 minutes. On a metered mobile connection this is a meaningful battery, data and latency cost, and it
scales with the app's telemetry traffic (see [M-27], which posts ~90-field payloads).

**Action:** make a single `static final OkHttpClient` (this is OkHttp's documented requirement), drop
timeouts to sane values (10–30 s), and enable connection pooling + HTTP/2. Migrating to OkHttp 4 +
Retrofit + coroutines would also retire `AsyncTask` ([L-25]).

#### [H-21] A JNI call **and** a SharedPreferences disk write on every request built
**Where:** `sources/com/mybedy/antiradar/profile/OauthInstance.java:341-348`, called **86 times** from
`NetworkHelper`'s request builders.

```java
public static String Z0() {
    if (!NavApplication.get().isCoreInitialized()) return "https://api.mybedy.com";
    String strNativeGetRecentCountry = NavigationEngine.nativeGetRecentCountry();  // JNI call
    y21.U0(strNativeGetRecentCountry);                                           // prefs .apply()
    return strNativeGetRecentCountry.equalsIgnoreCase("Russia") ? … : …;
}
```

and `sources/defpackage/y21.java:196-198`:

```java
public static void U0(String str) {
    NavApplication.prefs().edit().putString("recentCountry", str).apply();
}
```

**Why it matters:** `.apply()` is asynchronous but still commits to disk and wakes the fsync path.
Doing this on **every single request construction** — synchronously, before the request is even
dispatched — is pure waste for a value that changes at most once per trip. It also means every URL
construction touches shared prefs, which contends with the hot GPS/prefs path.

**Action:** cache the resolved base URL in a field, refreshed only when
`NavigationEngine.nativeGetRecentCountry()` actually changes (or on location-update events).

#### [M-22] Foreground and background fan-out paths are copy-pasted and have silently diverged
**Where:** `sources/com/mybedy/antiradar/service/MainServiceTrigger.java:67-137` — `k.updateLocation`
contains two ~30-line blocks: foreground `:72-101`, background `:112-134`.

**Concrete divergences already present:**
| | Foreground (`:72-101`) | Background (`:112-134`) |
|---|---|---|
| `AudioEngine.INSTANCE.i(…)` position | line 92 (before UI) | line 134 (**after** UI) |
| `J(sp, list)` → background widget | line 94 | **absent** |
| `MainService.v(…)` overlay | line 93 | **absent** (by design — app is backgrounded) |
| `nativeGetSeqSchemes()` | line 93, **unconditional** | guarded by `nativeIsActiveSeqs()` at :115 |
| Hazard list passed to observers | `:89` — **unfiltered** `hazardStateArr` | `:118` — **unfiltered** too |
| Hazard list passed downstream | `:93-100` — filtered `arrayList` | `:132` — filtered `arrayList2` |

Line 93 in particular calls `RadarDetectorEngine.nativeGetSeqSchemes()` **without** the
`nativeIsActiveSeqs()` guard that lines 86 and 115 apply — an extra unconditional JNI call plus a
possible mismatch between the value the observers saw and the value the overlay gets.

The same unfiltered/filtered split at `:89` vs `:93` is a latent contract bug: `StateObserver`
implementors receive hidden hazards that every other consumer has filtered out.

**Why it matters:** two copies of safety-critical dispatch logic that have already drifted apart is
exactly the shape of bug where a warning reaches the HUD but not the audio, or the widget shows a
camera the map does not.

**Action:** extract one method taking a `boolean background` (or an explicit target mask), and make the
hidden-hazard filtering a single decision applied before fan-out.

#### [M-23] Four main-looper timers; the 10 ms heading timer keeps running in PiP
**Where:** `sources/com/mybedy/antiradar/NavFragment.java:65-68` — four `Handler(Looper.getMainLooper())`
instances driving the render loop; `createTimers()` `:344-347`, `destroyTimers()` `:355-363`.

```java
public void destroyTimers() {
    destroyVelocityMoveTimer();
    destroyVelocityScaleTimer();
    if (NavigationEngine.nativeIsPip()) {   // ← :358  early return
        return;
    }
    destroySmoothMovingTimer();
    destroySmoothHeadingTimer();
}
```

Cadences: smooth-move `postAtTime` every **66 ms** (~15 fps, `:330`), heading every **10 ms** (~100 Hz,
`:207`), velocity-scale every **13 ms** (`:167`), inertia every **13 ms**.

**Why it matters:** in PiP the smooth-move **and** 10 ms heading timers are deliberately left running.
A 10 ms main-thread timer is ~100 wakeups/second — a significant, sustained battery draw on a device
whose entire purpose is to run for hours in a car. It is plausibly intentional (animated PiP map), so
this should be validated with a profiler rather than assumed.

**Action:** drive the heading timer from Choreographer instead of a fixed 10 ms `postDelayed`; consider
dropping to 33 ms in PiP. Add a battery-profiler trace before and after.

#### [L-24] GPS callback thread is implicit
**Where:** `sources/com/mybedy/antiradar/location/GoogleFusedLocationProvider.java:76`

```java
fusedLocationProviderClient.requestLocationUpdates(this.f, this.e, Looper.myLooper());
```

`Looper.myLooper()` binds the GPS callback to whatever thread happened to trigger the GMS client
connection. This directly determines which thread runs the unsynchronized `sn0` iteration described in
[H-15], so making it explicit is a prerequisite for fixing that race deterministically.

**Action:** pass `Looper.getMainLooper()` explicitly (or a dedicated named handler thread), then fix
the `sn0` snapshot iteration.

---

### 3.5 Architecture & Maintainability

#### [M-25] Five modules are far past a maintainable size
| File | Lines | Note |
|---|---|---|
| `profile/OauthInstance.java` | 2,701 | 145 KB; the whole auth + API surface |
| `core/RecordTypeHelper.java` | 2,531 | 67-entry hazard constant table (also owns all drawing logic) |
| `profile/AppProfile.java` | 2,453 | 129 KB |
| `profile/NetworkHelper.java` | 2,270 | 137 KB; **41** near-identical `Request.Builder()` shapes |
| `MainActivity.java` | 1,949 | 87 KB; implements 7 interfaces + `gu` (`:103`) |
| `preference/user/PrefUserDataFragment.java` | 1,855 | |
| `service/notifications/TransformNotifications.java` | 1,590 | |
| `background/widget/BackgroundWidget.java` | 1,560 | |
| `rd/RadarDetectorController.java` | 1,273 | the HUD |

`NetworkHelper.java` in particular is pure duplication: 41 sites repeat the same
`.addHeader(CONTENT_TYPE,"text/plain").addHeader(AUTHORIZATION,…)` chain. That is exactly what a
declarative/Retrofit layer replaces.

**Action:** extract the repeated builder chain into a typed request-spec (Retrofit interface, or at
minimum one private helper). Split `MainActivity` along its existing interface boundaries — it already
declares 7 seams to split on.

#### [M-26] Dead code: two unreachable auth backends
**Where:** `JSONService.AuthServiceType.FIREBASE` and `.COGNITO` (`profile/JSONService.java:68-72`,
branches `:76-79`) → `profile/d.java` (15,301 bytes) and `profile/CognitoInstance.java` (17,167 bytes).
Verified unreachable: the only `new JSONService(...)` is `AppProfile.java:2320` with `OAUTH2`.

**Why it matters:** 32 KB of unreachable code that still ships an internal AWS IP (see [M-11]) and
keeps a third auth design alive to maintain.

**Action:** delete both classes and the enum constants.

#### [L-27] Misleading names (obfuscation collateral)
- `service/SensorRestarterBroadcastReceiver` — **nothing to do with sensors**; it is the
  keep-alive that restarts `MainService` after an OS kill (`MainService.onDestroy` `:398-404`).
- `MainServiceTrigger` — an orchestrator, not a trigger; owns the per-fix pipeline.
- `rd` = "Radar Detector", not "road detection" (`rd/RadarDetectorController.java`).
- `NavigationEngine` — not a navigation engine; 221 of its natives are POI CRUD, a track recorder and
  ~60 setting getters/setters.

#### [L-28] Fragile constant reuse
`widget/app/AppWidgetStatistics.java:265` and `:270` fetch `AlarmManager` via
`context.getSystemService(NotificationCompat.CATEGORY_ALARM)`. This works only because
`NotificationCompat.CATEGORY_ALARM` happens to equal `"alarm"`, the same string as
`AlarmManager.SYSTEM_SERVICE`. A one-character change in an AndroidX constant silently returns the
wrong service and `setInexactRepeating` throws. Use `context.getSystemService(AlarmManager.class)`.

---

### 3.6 Feature Gaps & Product

#### [M-29] No first-party diagnostics anywhere
Verified: **zero** call sites for Firebase Analytics, Firebase Crashlytics, `FirebaseMessaging` or
datatransport in app code. The only observability is `printStackTrace` (6), `System.out.println` (6)
and `Log.*` (16) — all discarded on a release build.

**Why it matters:** this is why [M-10] (a feature broken since API 28), [H-17] (silent `System.exit`)
and [M-18] (42 swallowed exceptions) are all invisible in production. An app whose users trust it for
braking warnings cannot currently answer "why did the warning not fire on my phone?"

**Action:** add Crashlytics (or equivalent) with custom keys for `isCoreInitialized`,
`NavApplication.backgroundTracker().m()`, `WebAssetManager.INSTANCE.T()` (download-in-flight),
`AudioEngine` state and `ttla` sign-in state — these are exactly the states that determine whether a
warning fires. Capture non-fatals for the swallowed exceptions in [M-18].

#### [M-30] No offline queue or retry for user-generated submissions
One-tap crowd-sourced reporting is the product's social hook (three notification actions →
`MainService.c(int)`, `:111-122`). But the upload path is fire-and-forget: on failure there is no
retry, no local outbox, and the user gets no feedback. `AppProfile.x1()`'s `/user/payments` upload has
**both** callbacks as empty no-ops (`AppProfile.java:2706-2718`).

**Why it matters:** in a car, connectivity is intermittent by definition. Reports and corrections —
the data that makes the app better for everyone — are silently lost exactly when users are most
motivated to file them.

**Action:** a Room-backed outbox with WorkManager retry + backoff, and a visible "pending / synced"
indicator on the user's own reports.

#### [L-31] No refresh-first on 401 — a transient error logs the user out
**Where:** `profile/NetworkHelper.java:1072-1091` — on HTTP 401 with `error_description` starting
`"Invalid access token"`, `a2()` forces a full re-sign-in instead of attempting a refresh first.

**Action:** try `grant_type=refresh_token` (`NetworkHelper.java:581`) once, then fall back to re-auth.

#### [L-32] Deprecated APIs in active use
| API | Where | Since |
|---|---|---|
| `android.os.AsyncTask` (`RequestRegStat`) | `widget/app/AppWidgetStatistics.java:41` | API 30 |
| `getParcelableExtra(String)` | `service/BluetoothConnectionReceiver.java:66, 82` | API 33 — use the typed `getParcelableExtra(name, Class)` overload |
| `com.squareup.okhttp` (OkHttp 1.x) | `profile/` throughout | superseded by `okhttp3` |

---

### 3.7 UX & Accessibility

Accessibility is **not** ignored here: 20 `content_desc` string resources exist, 16
`contentDescription`/`importantForAccessibility` calls appear in Java, `supportsRtl="true"` is set
(manifest:101), and 122 locale resource directories are shipped. The gaps below are coverage, not intent.

#### [M-33] 40% of images have no accessibility label
**Where:** `resources/com.mybedy.antiradar.apk/res/layout/` — of **598** `<ImageView>` / `<ImageButton>`
elements, **239** declare neither `android:contentDescription` nor
`android:importantForAccessibility`.

Worst layouts: `lay_card_page_reviews.xml` (17), `lay_card_page_fuel.xml` (14), `lay_iab_product.xml`
(10), `frg_intro.xml` (8), `lay_card_page_attrs_two.xml` (7), `lay_navigation_search.xml` (7),
`lay_card_page_attrs_one.xml` (6), `lay_card_page_stars.xml` (6).

**Why it matters:** a TalkBack user cannot perceive the hazard cards, the fuel card, or the purchase
button. For a card that communicates a *speed camera's type and limit*, the visual is the entire
message.

**Action:** add `contentDescription` to every interactive and information-bearing image; mark purely
decorative ones `android:importantForAccessibility="no"`. Add a lint gate
(`MissingContentDescription`) to CI so this cannot regress.

#### [L-34] 23 hardcoded strings in layouts block localization
**Where:** 12 layout files under `res/layout/` — `menu_content.xml` (5), `frg_profile.xml` (5),
`lay_image_chooser.xml` (2), `lay_user_data.xml` (2), `ime_secondary_split_test_activity.xml` (2), and
6 more. Also `setText("0")` / `setText("0:00")` as non-localizable placeholders in
`background/widget/BackgroundWidget.java:1222, 1340` and
`preference/user/PrefUserDataFragment.java:478`.

**Why it matters:** small, but the app already ships 122 locales — this is unfinished work, not a
deliberate trade-off.

#### [L-35] The map surface is inaccessible to non-touch and assistive input
**Where:** `sources/com/mybedy/antiradar/NavFragment.java:447-600` — hand-rolled `onTouch` with
1000 ms long-press (`:154, :461`), 300 ms double-tap window (`:31`), 500 ms / 50 px·s⁻¹ drag
discriminator (`:506`), 250 px·s⁻¹ inertia threshold (`:525`).

**Why it matters:** no `GestureDetector`, and — more importantly — the GL surface exposes no
accessibility actions, so a TalkBack or Switch Access user cannot pan, zoom or select a hazard on the
map. It also cannot be driven by a stylus/keyboard or by an external switch.

**Action:** add `AccessibilityNodeInfo` actions for pan/zoom/select-hazard on the surface, or expose
the same hazard list through a focusable content view that mirrors the HUD.

---

## 4. Prioritized roadmap

### Quick wins — low risk, high return (target: next release)

| # | Item | Ref |
|---|---|---|
| 1 | **`sn0`: `try/finally` around `finishIterate()`, snapshot iteration, per-observer `try/catch`** — removes a permanent crash loop in the core pipeline | [H-15] |
| 2 | **Fix the live statistics widget endpoint** — it has been broken on API 28+; point it at the production HTTPS host or delete it | [M-10] |
| 3 | **Single shared `OkHttpClient`** with sane timeouts — removes a TLS handshake per request across 87 call sites | [H-20] |
| 4 | **Cache the API base URL** — remove a JNI call + prefs write from each of 86 request builds | [H-21] |
| 5 | **`exported="false"`** on `StartServiceActivity`, `StopServiceActivity`, `MainActivity`, and the three unprotected system receivers | [H-8] [M-9] |
| 6 | **Clear all four credential keys on sign-out** (`acct`, `reft`, `AppUsernameLogin`, `AppSecretLogin`) + server-side token revoke | [H-6] |
| 7 | **Fix `requestPinAppWidget`** — pass a `null` Intent | [L-19] |
| 8 | **Delete `app.xapk`** (byte-identical duplicate, 46 MB) and the dead `FIREBASE`/`COGNITO` backends | [M-2] [M-26] |
| 9 | **`getSystemService(AlarmManager.class)`** instead of the `CATEGORY_ALARM` string | [L-28] |

### Medium-term — next 1–2 releases

| # | Item | Ref |
|---|---|---|
| 10 | **Migrate the map/voice/model CDN to HTTPS** and drop the cleartext allowlist entries | [H-7] |
| 11 | **Stop persisting the password**; depend on the refresh token alone | [H-5] |
| 12 | **Server-side IAP verification** — signature check + Play Developer API + server-side entitlement | [H-13] |
| 13 | **Move all client-secret-gated endpoints behind a backend proxy; adopt PKCE** | [H-4] |
| 14 | **Reorder `StorageManager.k()` to copy → verify → switch → delete**, with a resumable manifest | [H-16] |
| 15 | **Unify the foreground/background fan-out** in `MainServiceTrigger.k` into one method; apply hidden-hazard filtering once, before fan-out | [M-22] |
| 16 | **Migrate `profile/` and `downloader/` to OkHttp 4 + Retrofit + coroutines**; retire `AsyncTask`; replace `f.java`/`e.java` with `HttpUrl.Builder`/`FormBody` | [M-12] [L-32] |
| 17 | **Add Crashlytics** with custom keys for the warning-pipeline state | [M-29] |
| 18 | **Accessibility pass**: label the 239 unlabelled images, add lint gate, add map accessibility actions | [M-33] [L-35] |
| 19 | **Refresh-first on 401** instead of forced re-auth | [L-31] |
| 20 | **Replace `System.exit(0)`** on wrong ABI with a recorded fatal error | [M-17] |

### Long-term — architectural

| # | Item | Ref |
|---|---|---|
| 21 | **Server-signed asset manifest** — the only durable fix for map-database integrity; do this *after* HTTPS | [H-7] |
| 22 | **Offline outbox + WorkManager retry** for crowd-sourced reports and corrections | [M-30] |
| 23 | **Decompose the five oversized modules**; start with `NetworkHelper`'s 41 duplicated builder chains | [M-25] |
| 24 | **Render-loop battery work**: Choreographer-driven heading, profile the 10 ms PiP timer | [M-23] |
| 25 | **Make the GPS callback thread explicit**, then rely on `sn0`'s snapshot iteration to be race-free | [L-24] |
| 26 | **Plan the JNI boundary**: document all ~400 `native` signatures as a stability contract; the current coupling to a prebuilt `.so` blocks refactoring of everything it touches | §3.5, [H-1] |

---

## 5. Tooling, testing & CI

### Current state: none

Verified absent from `D:\contracam`: no build system, no VCS, no test source sets, no linter or
type-checker configuration, no CI. There is no `test/`, `androidTest/`, or `src/` directory anywhere
outside `.opencode/node_modules`.

### Recommended baseline (in the upstream repository, not here)

1. **Build.** Gradle + AGP with version catalog; `minSdk 32` / `target 36` already set. Add
   `abiFilters "arm64-v8a"` explicitly so the ABI mismatch in [M-17] is impossible to ship.
2. **Static analysis, tiered and non-blocking initially:**
   - Android Lint: `MissingContentDescription`, `BatteryLife`, `ObsoleteSdkInt`,
     `AllowBackup`, `TrustAllX509TrustManager`, `UnsafeOptInUsageError` — promote to error on new code.
   - **Detekt / PMD** for the Java layer: `EmptyCatchBlock`, `AvoidPrintStackTrace`,
     `AvoidCatchingGenericException`, `TooManyMethods`, `CyclomaticComplexity` (this alone flags
     [M-18], [M-25]).
   - **Dependency scanning** (`dependency-check`, Snyk, OSS-Review) — the app ships Firebase, GMS,
     Billing and OkHttp 1.x.
   - **Secret scanning** (gitleaks / `detect-secrets`) with a CI gate — would have caught [H-4] at
     commit time.
3. **Tests — target the pure-logic seams first.** JNI means most classes cannot be unit-tested as-is;
   inject a `Engine` interface over the ~400 natives. Highest-value first targets, all currently
   untestable-but-should-be:
   | Target | Why |
   |---|---|
   | `profile/f`, `profile/e` query/form builders | pure functions; would have caught [M-12] |
   | `core/HazardState.isDangerous()` / `isSpeedControl()` | 67-entry / ~35-ordinal constant tables |
   | `core/SpeedometerState` constructor + sign lookup | would have resolved the [open question](#6-corrections-to-existing-docs) below |
   | `rd/RadarDetectorController.c0()` slot assignment | safety-critical HUD logic |
   | `defpackage/sn0` | concurrent add/remove/iterate — would have caught [H-15] |
   | `location/d` / `location/e` accuracy arbitration | pure, no Android deps |
4. **Instrumentation tests** for the JNI boundary: assert that every native declaration still resolves
   (`System.loadLibrary` + a reflective sweep over the 400 signatures). This directly guards the
   invariant in `AGENT.md` §1.2 and would fail loudly at build time instead of at runtime on a user's
   device.
5. **CI pipeline** on every push: `lint` → `detekt` → `unit test` → `connectedAndroidTest` (single
   arm64 emulator) → `assembleRelease` → **dependency + secret scan** → artifact retention.
6. **Release gates that map to this report:**
   - `0` exported components without a declared permission (or an explicit allowlist entry).
   - `0` cleartext domains in `network_security_config.xml`.
   - `0` `EmptyCatchBlock` violations in `com.mybedy.antiradar/**`.
   - `0` hardcoded `https?://` literals outside a single constants file.

---

## 6. Corrections to existing docs

Three claims in `AGENT.md` / `code.md` were checked against the code and need correcting or qualifying.

### 6.1 `SpeedometerState.java:85-87` is **not** a decompiler glitch — and should not be "fixed"

`AGENT.md` §6 lists this as a bug ("decompiler glitch", "per-limit sign art is not being selected")
and prescribes a fix. Reading the surrounding code, **I believe that is a misdiagnosis.**

```java
// SpeedometerState.java:85-88
if (i >= 5 && i <= 185) { i = 5; }
this.speedometerSign = SPEEDOMETER_SIGN_MAP.get(i, SpeedometerSign.MAX_SPEED_SIGN_NON);
```

The map is built from the nested enum (`:37-41`), which has exactly **four** constants:

| Constant | `mSpeedometerRes` | `mSpeedometerId` | `backgroundId` |
|---|---|---|---|
| `MAX_SPEED_SIGN_BECOME_NARROW` | `R.drawable.ivc_become_narrow` | −65 | 2 |
| `MAX_SPEED_SIGN_NO_OVERTAKING` | `R.drawable.ivc_no_overtaking` | −64 | 2 |
| `MAX_SPEED_SIGN_NON` | 0 | 0 | 0 |
| `MAX_SPEED_SIGN` | 0 | **5** | 2 |

Only `-65`, `-64`, `0` and `5` can ever be keys. The two structural sign flags are matched exactly
by the raw id. Any remaining id — i.e. a real speed limit in 5…185 — has **no** distinct artwork to map
to, so the clamp normalises it onto `MAX_SPEED` (id 5). And because `MAX_SPEED_SIGN.mSpeedometerRes` is
`0` (meaning "no specific sign graphic"), that resolves to the same visual outcome as the
`MAX_SPEED_SIGN_NON` default. **The clamp is a no-op with respect to rendered output.**

The residual oddity is `mSpeedometerRes = 0`, which is why `R.drawable.0` appears in the decompile —
and `R.drawable.0` is not a valid Java identifier, so that literal is itself a decompilation artefact of
an inlined `0` sentinel. It is not a crash.

> **Uncertainty:** this is a decompile; R8 could theoretically have constant-folded something. But the
> logic is self-consistent and the enum table confirms the behaviour, so I would **not** spend a release
> on "fixing" this. If the product team genuinely wants per-limit sign artwork, that is a **feature**
> (new drawables + new enum constants), not a bug fix.

### 6.2 `PendingIntent` mutability is already correct — not a finding
Several audits flag missing `FLAG_IMMUTABLE`. Every `PendingIntent` site in app code passes
`201326592` = `0x0C000000` = `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE` (verified at
`AppWidget.java:32,61`, `AppWidgetLaunchModes.java:19,25,32,39`, `AppWidgetStatistics.java:164,214,265,270`,
`AppWidgetTransportProfiles.java:20`). This is correct and needs no work.

### 6.3 The `NetworkHelper` client-secret call count is 17, not 16
`code.md` §9.1 says 16 and lists 13. Verified: **16** in `NetworkHelper.java` + **1** in
`AppWidgetStatistics.java:60` = **17 total**. The extra one is significant because it targets a
different host over plain HTTP ([M-10]).

---

## 7. What could not be verified

| Area | Limitation |
|---|---|
| `libVestigoEngines.so` (~85% of logic) | Binary only. `nativeVerifyImageChecksum` semantics, spatial index, proximity maths, routing and asset unpacking are **unverified** — the map-tampering severity in [H-7] hinges entirely on this. |
| Runtime behaviour | Static analysis only. Nothing was executed. [M-10] (blocked cleartext call) and [H-15] (crash loop) are the two findings that most deserve a 10-minute device test to confirm. |
| Thread affinity of the GPS callback | [L-24] — `Looper.myLooper()` means the answer depends on which thread triggers the GMS connection. Resolving it empirically would make the [H-15] race analysis exact. |
| R8 decompilation fidelity | ~9,103 files of decompiled output. Control flow, generics and try/catch boundaries are reconstructed, not original. Line numbers cited here are decompiled line numbers. |
| PiP timer intent | [M-23] — leaving the 10 ms heading timer running in PiP may be intentional animation. Needs a battery trace, not a code reading, to decide. |
| UX/product decisions | Findings in §3.7 are derived from code and resource inspection. They have not been validated with users, and the two manual `§1` `rn`-era flows may reflect deliberate product choices. |
| Server behaviour | [H-4]/[H-13] severities assume the backend does not independently validate. Not verifiable from here. |
