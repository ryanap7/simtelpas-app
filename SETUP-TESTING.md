# SIMTELPAS — Feature-Parity Test Install (no device-owner)

Use this when the only thing you need to check is: **does the menu/feature
set the app shows match what's checked for that device/institution in the
admin panel** — not whether kiosk lockdown works. It skips `dpm
set-device-owner` entirely, so it's faster to set up and doesn't touch USB
debugging, app uninstall, or factory reset.

**This is not the imaging procedure.** For any tablet that will actually
leave the bench and go into a Blok, use `SETUP.md` instead — that's the only
path that applies kiosk lockdown. A device set up with this doc stays fully
open (adb, uninstall, factory reset all work) and must never ship.

## Why this works without device-owner

- `MainActivity` is just a full-screen `WebView` pointed at
  `https://app.simtelpas.com` (`MainActivity.kt:291`). There is no native
  code that fetches or filters a feature list — the admin-panel checklist →
  menu rendering happens entirely inside that web app, keyed off the
  `deviceId` the native app injects as `window.__deviceId`
  (`injectDeviceContext()`, `MainActivity.kt:327-331`). So this procedure
  only needs to get you to the point where that WebView is loaded and
  correctly identified — the actual "does it match the checklist" check is
  then a visual check against the web page, same as it would be after a full
  imaging.
- `SetupActivity` (the thing that flips the device into "registered") only
  checks `DevicePreferences.isRegistered(this)` — it never checks
  `dpm.isDeviceOwnerApp()` (`SetupActivity.kt:29-35`). It runs identically
  whether or not `dpm set-device-owner` was ever called.
- Every lockdown call in `KioskModeManager` (`applyRestrictions`,
  `applyFullLockdown`) starts with `if (!dpm.isDeviceOwnerApp(...)) return`
  (`KioskModeManager.kt:66-69`, `:109-114`). Without device-owner these are
  guaranteed no-ops — nothing throws, nothing partially applies.

## Steps

1. **Build a debug APK** — avoids needing the release keystore for a
   throwaway test build:
   ```
   ./gradlew assembleDebug
   ```
   `DEVICE_API_KEY` in `local.properties` is still required (it's read in
   `defaultConfig`, so every variant needs it — see SETUP.md §3a if you
   haven't set this up on this machine yet).
   Output: `app\build\outputs\apk\debug\app-debug.apk`

2. **Install normally** — any tablet or emulator with USB debugging on, no
   factory reset needed:
   ```
   adb install app-debug.apk
   ```

   **`adb install` by itself can never make this device-owner.** Device-owner
   is only ever granted by explicitly running `adb shell dpm
   set-device-owner ...` (or completing QR/NFC managed provisioning) — that's
   the only trigger for `AdminReceiver.onEnabled()` (`AdminReceiver.kt:24-28`),
   which is the only place that applies any lockdown. Plain install + launch
   does not touch it. As long as you don't run the `dpm set-device-owner`
   command from SETUP.md, there is nothing in this procedure that can lock
   the device — you can `adb install` / `adb uninstall` this test build as
   many times as you want.

3. **Get a deviceId + one-time setupToken from the admin panel**, same as
   SETUP.md step 6 (pre-create/select the device record whose feature
   checklist you want to verify). This step is still required —
   `MainActivity` won't show the WebView at all until
   `DevicePreferences.isRegistered()` is true (`MainActivity.kt:86-94`); an
   unregistered device just sits on the "Menunggu Setup Perangkat" screen.

4. **Register the device** — no `dpm set-device-owner` beforehand:
   ```
   adb shell am start -n com.sdn.simtelpas/.ui.SetupActivity --es deviceId "<DEVICE_ID>" --es setupToken "<SETUP_TOKEN>"
   ```

5. **Confirm registration, not lockdown**:
   ```
   adb logcat -s SimtelpasProvisioning:I -d
   ```
   Expect:
   ```
   registration_saved: deviceId=<DEVICE_ID>
   claim_worker_enqueued: deviceId=<DEVICE_ID>
   ```
   followed shortly by `device_claimed: ok` once the claim call reaches the
   backend (needs network). **You will not see `device_owner_enabled` or
   `full_lockdown_applied`** — both are logged only on the path that has
   device-owner; their absence here is expected, not a failure.

6. **Check the feature list.** `MainActivity` relaunches, loads
   `https://app.simtelpas.com` with `window.__deviceId` set, no lockdown
   applied — Home/Back/Recents all work normally. Log in / navigate as you
   normally would and compare the menu items shown against the checklist for
   that device/institution in the admin panel.

   Expect a one-time **"This app is pinned"** system dialog on first launch.
   That's plain Android screen-pinning (`enterLockTask()` is called
   unconditionally in `MainActivity.onCreate`/`onResume` regardless of
   device-owner status) — not device-owner lock task. Tap **Got it** and you
   can still exit anytime via swipe-up-and-hold from the bottom of the
   screen, no admin PIN needed. If a camera/mic permission prompt also
   appears, that's the WebView's video-call feature asking normally; answer
   it however you want to test that flow.

   ### What "checking the feature list" actually looks like on-page

   The web app gates whole sections behind boolean flags on a `deviceFeatures`
   object (confirmed from the "Komunikasi" section's source — that component
   isn't part of this Android repo, it lives in the web frontend). Example
   from that section:

   - `deviceFeatures.featureCall` → shows the `CallCard`
   - `deviceFeatures.featureVoiceMessage` → shows the `VoiceMessageCard`
   - `deviceFeatures.featureTextMessage` → shows the `ChatCard`
   - If all three are `false`, the section collapses to a single "Fitur
     Komunikasi Tidak Aktif" placeholder instead of three empty cards.

   So the check per admin-panel checkbox is: toggle it off for the test
   device/institution → the matching card should disappear from the page
   (and if it's the last one in its section, the section should show its
   "Tidak Aktif" placeholder, not just a blank gap) → toggle it back on →
   card reappears. This is the pattern to repeat for every other
   feature-gated section on the page, not just Komunikasi.

   ### Verifying against the actual API response, not just eyeballing the page

   `setupWebView()` now calls `WebView.setWebContentsDebuggingEnabled(true)`
   on debug builds only (`MainActivity.kt`, gated on `BuildConfig.DEBUG` —
   never runs on a release build, so this doesn't change anything about what
   ships to a Blok). With that in place:

   1. Keep the tablet connected over USB (or use an emulator).
   2. On the imaging PC, open Chrome and go to `chrome://inspect#devices`.
   3. Under the device entry, find `app.simtelpas.com` and click **inspect**
      — this opens normal Chrome DevTools attached to the WebView.
   4. Use the **Network** tab to find whatever request the page makes for the
      device's feature config (filter by XHR/Fetch, reload the page) and
      read the raw JSON response — that's the actual `deviceFeatures` payload
      the API returned, straight from the source, not the rendered UI.
   5. Compare that JSON against what's checked in the admin panel for this
      device/institution, and separately against which cards actually
      rendered in step 6 above. A mismatch tells you exactly where the bug
      is: admin panel vs. API response (backend bug) vs. API response vs.
      rendered cards (frontend bug) — instead of just "the app is wrong."

7. **Re-test a different device/institution config** without reinstalling:
   ```
   adb shell pm clear com.sdn.simtelpas
   ```
   This wipes `DevicePreferences` (including the saved `deviceId`), so
   `isRegistered()` goes back to `false` and step 4 can run again with a
   different `deviceId`/`setupToken`. Without this, `SetupActivity` just
   no-ops on a second run (`SetupActivity.kt:29-35`) since it refuses to
   re-register an already-registered device.

## When to switch to the real SETUP.md instead

If the question changes from "does the right menu show up" to "does the
kiosk actually lock down correctly" (uninstall blocked, USB debugging cut,
persistent-preferred-HOME, admin-PIN boundary, etc.), this doc's device
proves nothing — none of that lockdown code ever ran here. Wipe this test
device (`pm clear`, or just factory reset it) and follow `SETUP.md` from
scratch.
