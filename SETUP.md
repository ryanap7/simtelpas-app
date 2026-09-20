# SIMTELPAS — Per-Device Imaging via adb

This is the imaging procedure for tablets provisioned by `adb shell dpm
set-device-owner` (QR/NFC managed provisioning is blocked by the Android
Enterprise allowlist in this environment, so this is the supported path).

**Just need to eyeball whether the app's feature list matches the admin
panel checklist, not test kiosk lockdown?** See `SETUP-TESTING.md` instead —
it skips `dpm set-device-owner` entirely. Don't ship a device set up that
way; it stays fully unlocked (adb, uninstall, factory reset all work).

Package: `com.sdn.simtelpas`
Device admin component: `com.sdn.simtelpas/.AdminReceiver`

**Two adb commands are required, in this exact order**, plus one manual step
between them. Getting the order wrong (running step 5 before step 6, or on a
debug-build APK) is the difference between a device that locks down correctly
and one that's silently unregistered or leaves adb open in production — see
"Why the order matters" below.

## 1. Factory reset checklist

- Factory reset the tablet (Settings → System → Reset options → Erase all
  data) or start from a freshly flashed image.
- Confirm no Google account or other device-owner-blocking account is already
  present — `dpm set-device-owner` fails if any account exists on the device.

## 2. Setup wizard

- Skip Wi-Fi setup if possible (connect Wi-Fi after imaging instead), and skip
  every account-login prompt. Do not sign into a Google account.
- Skip to the home screen / "Setup complete" as fast as the wizard allows.

## 3. Enable Developer Options + USB debugging (temporary)

- Settings → About tablet → tap "Build number" 7 times.
- Settings → System → Developer options → enable **USB debugging**.
- Connect the tablet to the imaging PC and accept the "Allow USB debugging?"
  prompt on-device.

## 3a. Build prerequisite: DEVICE_API_KEY (before building the release APK)

The build reads `DEVICE_API_KEY` from `local.properties` (gitignored, never
committed) and fails immediately if it's missing — see
`local.properties.example` for the exact line to add:

```
DEVICE_API_KEY=<ask backend/SIMTELPAS admin team for the current key>
```

Without this, `./gradlew assembleRelease` fails before producing an APK — you
cannot accidentally ship a build with an empty or stale key baked in. This is
a one-time local setup step on the build machine, not something done per
device.

On a build machine without a standalone JDK on `PATH`, `gradlew` fails with
`JAVA_HOME is not set and no 'java' command could be found`. Point it at
Android Studio's bundled JBR instead of installing a separate JDK:

```
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # PowerShell, this session only
```

**What this does and doesn't protect against:** moving the key into
`local.properties` keeps it out of git — anyone who clones this repo no
longer gets the key for free. It does **not** remove the key from the
compiled APK: `BuildConfig.DEVICE_API_KEY` is a compile-time constant, baked
into `classes.dex` exactly like a hardcoded string would be. Anyone with the
APK file itself can still extract it (this is true of any client-embedded API
key in any Android app, not specific to this build setup). If the key needs
to survive compromise of a physical tablet, that requires a different
architecture entirely (per-device credentials, short-lived tokens, mTLS) —
out of scope for this change.

## 4. Install the app

Use the **release** build, not debug — see "Why the order matters" below for
why this matters in production.

```
adb install app-release.apk
```

(Run from the directory containing the APK — Gradle actually outputs it to
`app\build\outputs\apk\release\app-release.apk`, not the repo root; use that
path, or `cd` there first.)

Verify it landed:

```
adb shell pm list packages | findstr simtelpas
```

## 5. Set device owner

```
adb shell dpm set-device-owner com.sdn.simtelpas/.AdminReceiver
```

Expected output: `Success: Device owner set to package com.sdn.simtelpas`.
This fires `AdminReceiver.onEnabled()`, which immediately pins the app into
lock-task mode and applies the restrictions that are safe pre-registration
(lock-task package/feature lockdown, `DISALLOW_INSTALL_APPS`,
`DISALLOW_UNINSTALL_APPS`, `DISALLOW_SAFE_BOOT`, `DISALLOW_FACTORY_RESET`,
`DISALLOW_ADD_USER`, and `DISALLOW_APPS_CONTROL` on API 30+). The tablet will
show a "Menunggu Setup Perangkat" screen, still kiosk-locked — this is
expected until step 6.

## 6. Register the device (adb companion to QR provisioning)

Get a `deviceId` + one-time `setupToken` for this device the same way the QR
flow would (pre-create the device record on the backend / admin panel), then:

```
adb shell am start -n com.sdn.simtelpas/.ui.SetupActivity --es deviceId "<DEVICE_ID>" --es setupToken "<SETUP_TOKEN>"
```

This saves the device registration, enqueues the backend claim
(`POST /devices/:id/claim`, which returns the bcrypt admin-PIN hash), and
applies full lockdown: persistent-preferred-HOME (no "choose launcher" dialog
ever again), keyguard shortcuts disabled, and — because this is a release
build — USB debugging locked out via `DISALLOW_DEBUGGING_FEATURES`.

**adb disconnects automatically as part of this step on a release build.**
That's intentional (see below), not a failure.

## 7. Verify before unplugging

Run these **immediately after step 6, before the adb session drops**:

```
adb shell dpm list-owners
```
Expect: `Device owner: com.sdn.simtelpas/.AdminReceiver`.

```
adb logcat -s SimtelpasProvisioning:I -d
```
Expect these lines, in order:
```
device_owner_enabled: ok
registration_saved: deviceId=<DEVICE_ID>
claim_worker_enqueued: deviceId=<DEVICE_ID>
full_lockdown_applied: ok
```
`device_claimed: ok` (or `device_claim_failed: http=<code>`) will appear a
moment later, once the backend call finishes — it needs network, so check for
it separately if Wi-Fi wasn't connected yet at claim time. It's logged by
`DeviceClaimWorker`/WorkManager and will retry on its own once connectivity
returns; you can re-check with the same `adb logcat` command over a fresh USB
connection (before debugging is disabled) or, once online, watch for the
admin PIN dialog working from the volume-up admin menu.

If any log line is missing, do **not** ship the device — re-run step 6 (it's
safe to retry: `SetupActivity` no-ops if `DevicePreferences.isRegistered()` is
already true, so a partial failure needs investigating, not just re-running
blind — check logcat for `setup_activity_rejected` first).

### 7a. Verify the admin-PIN exit doesn't loosen anything it shouldn't

adb is already gone by this point on a release build, so this check has to be
done on-device, in the UI — not over adb:

1. Volume-up x5 within 3 seconds → enter the recovery/admin PIN → "Keluar
   Kiosk ke Settings".
2. In Settings, confirm all of the following are still **greyed out /
   disabled / hidden** (if any of these is selectable, stop and escalate —
   see below):
   - Settings → Apps → SIMTELPAS → **Uninstall** button
   - Settings → System → Developer options → **USB debugging** (the option
     may be fully hidden if Developer options itself got re-locked; either is
     correct)
   - Settings → System → Reset options → **Erase all data (factory reset)**
   - Settings → System → Multiple users → **Add user**
3. What's expected to work normally in this window: navigating Settings menus,
   status bar/quick settings, the normal lockscreen. That's the intentional,
   temporary relaxation — see "What the admin PIN can and can't unlock" below.
4. Return to the kiosk (reboot, or wait out the 10-minute admin-mode window) —
   `KioskModeManager.endAdminMode()` re-applies full lockdown automatically.

If step 2 ever shows one of those as enabled, that's a regression in
`KioskModeManager.relaxRestrictions()` — it must only touch persistent-
preferred-activity / keyguard features / lock-task feature bitmask, never the
`DISALLOW_*` user restrictions. Do not ship devices until that's fixed.

## 8. Disable USB debugging

On a **release** build this already happened automatically in step 6
(`DISALLOW_DEBUGGING_FEATURES` cuts the adb session as soon as full lockdown
applies). If for some reason it's still connected — e.g. this is a debug
build used for a test run — manually turn off Developer Options → USB
debugging before the device leaves the imaging bench. Never ship a tablet
into a Blok with debugging enabled.

---

## Why the order matters

- `adb shell dpm set-device-owner` has **no extras mechanism** — unlike QR/NFC
  provisioning, there's no `PROVISIONING_ADMIN_EXTRAS_BUNDLE` to carry
  `deviceId`/`setupToken`. Step 6's `am start --es` is how this app receives
  the equivalent data on the adb path.
- `DISALLOW_INSTALL_APPS`/`DISALLOW_UNINSTALL_APPS`/etc. (step 5) are safe to
  apply immediately — they don't affect adb.
- `DISALLOW_DEBUGGING_FEATURES` (step 6) **does** cut USB debugging. It's
  deliberately deferred out of step 5 and only applied once full lockdown
  runs in step 6, specifically so step 6's own `am start` command still has a
  working adb session to run over. If it were applied at step 5 instead, the
  adb connection would die before you could ever send the deviceId/setupToken,
  and the device would be stuck showing the waiting screen with no way to
  finish registration over USB.
- This restriction is gated on `BuildConfig.DEBUG` — a **debug build never
  applies it**, so USB debugging stays permanently open on a debug APK. Use
  the release build for anything that leaves the imaging bench.

## What the admin PIN can and can't unlock

The volume-up x5 + PIN admin menu (`AdminAccessManager` →
`KioskModeManager.beginAdminModeAndExit()` / `relaxRestrictions()`) only ever
relaxes **navigation** restrictions, for a 10-minute window:
persistent-preferred-HOME, keyguard-disabled-features, and the lock-task
feature bitmask (so Settings/status bar/notifications become reachable).

It never touches, under any circumstances: `DISALLOW_SAFE_BOOT`,
`DISALLOW_FACTORY_RESET`, `DISALLOW_INSTALL_APPS`, `DISALLOW_UNINSTALL_APPS`,
`DISALLOW_ADD_USER`, `DISALLOW_APPS_CONTROL`, `DISALLOW_DEBUGGING_FEATURES`.
Those are applied once in `KioskModeManager.applyFullLockdown()` (step 6) and
stay on for the device's entire time in service — knowing the admin PIN is
not enough to install/uninstall apps, factory reset, add a user, safe-boot,
or re-enable USB debugging.

**Using the admin PIN before step 6 (registration) has completed is safe.**
`KioskModeManager.endAdminMode()` (fired automatically when the 10-minute
window expires, via `AdminModeExpiryWorker`) checks
`DevicePreferences.isRegistered()` before deciding what to re-apply: if not
yet registered, it falls back to `applyRestrictions()` only, never
`applyFullLockdown()`. An earlier build of this app got this wrong —
`endAdminMode()` called `applyFullLockdown()` unconditionally, so an
admin-PIN excursion taken *before* step 6 (e.g. to poke at Developer options
while troubleshooting) would silently apply `DISALLOW_DEBUGGING_FEATURES` +
`DISALLOW_FACTORY_RESET` the moment the 10-minute window lapsed — cutting
USB debugging and hiding the Settings factory-reset option on a device that
was never actually registered, with no adb-based or in-app way back. If that
ever happens again (e.g. from a build that predates this fix), the only
recovery is a hardware recovery-mode wipe (Power + Volume Up from fully
powered off, holding both ~10s past the logo) — `DISALLOW_FACTORY_RESET`
blocks the Settings-app reset flow but not the bootloader-level recovery
wipe. Recovery mode requires holding the *combo*, not tapping the buttons in
sequence, and can take a couple of tries to register.

See 7a above for how to verify the admin-PIN boundary on a physical
device.

**There is currently no decommissioning/un-enrollment flow in this app** — no
call to `dpm.clearDeviceOwnerApp()` or `dpm.wipeData()` anywhere in the
codebase. A device that needs to be retired or re-imaged today has no
in-app or admin-PIN path to lift device-owner status; that needs to be
designed and built separately before it's needed operationally.

## SetupActivity is `exported="true"` — why, and why that's safe here

**Corrected understanding (as of the first real retail-hardware test — a
HONOR JMS-W09 running Android 15):** shell's exemption from the
exported-component check only applies on `eng`/`userdebug` builds (emulators,
dev devices). On a genuine `user`-build device — i.e. any retail tablet,
from any OEM, exactly like every unit that will actually ship into a Blok —
`adb shell am start -n <pkg>/<NonExportedActivity>` is **denied** with
`Permission Denial: ... not exported from uid 2000`, full stop. This was
never HONOR-specific; it would fail identically on a retail Pixel, Samsung,
or anything else. (Confirmed non-Activity components are blocked the same
way too — `adb shell am start-service` to a non-exported `Service` fails
with the identical error — and `adb root` is refused with "cannot run as
root in production builds", confirming `ro.build.type=user`.) An earlier
version of this doc claimed the opposite (shell is "long-standing,
documented" as exempt) — that was true only for the eng/userdebug builds it
was likely validated against, not for the release build that actually ships.

So `SetupActivity` is `android:exported="true"`, with **no** `signature`-level
`android:permission` — a signature permission would check the caller's
signing cert for every caller including `shell`, and `shell` isn't signed
with this app's release key, so it would just turn "not exported" into a
different `Permission Denial`. It doesn't fix anything; don't reach for it.

Making `SetupActivity` reachable by any caller (not just `shell`) is an
intentional, reviewed tradeoff, not an oversight — the real access control
was never IPC visibility, it's:
- `setupToken` is a one-time, high-entropy UUID validated server-side via
  `POST /devices/:id/claim`; nothing on-device lets an attacker forge or
  guess a valid one.
- `SetupActivity` no-ops immediately if `DevicePreferences.isRegistered()`
  is already `true` (`SetupActivity.kt`) — once claimed, the activity is
  inert for the rest of the device's service life, for any caller.
- `DISALLOW_INSTALL_APPS` is already active by the time `SetupActivity` is
  ever reachable (applied in step 5, `AdminReceiver.onEnabled` →
  `KioskModeManager.applyRestrictions`) — no third-party app can ever get
  onto the device during the window this activity is live to exploit that
  exposure in the first place.

If step 6 ever throws `Permission Denial` again on a future device, check
`android:exported` on `SetupActivity` first (should be `true`) before
assuming it's something environmental — this is no longer an expected
failure mode.

## Known platform limitation: power button menu

Global Actions (long-press power → power off / restart / emergency call) is
suppressed via `DevicePolicyManager.setLockTaskFeatures()` on API 28+ (P+) by
simply never including `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` in the bitmask —
this is the current recommended mechanism and requires no deprecated APIs.

On API 24–27 (below P), there is no equivalent `LOCK_TASK_FEATURE_*` API —
screen pinning on those versions blocks Home/Recents but Android does not
expose a documented way for a device-owner app to suppress the power-menu
during lock task. If any fleet tablets are genuinely stuck on API < 28, the
power button (short: screen off, long-press: power menu) remains reachable on
those units; this is a platform ceiling, not something fixable from
app code.
