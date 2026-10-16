# Android Optimizer

A native Android app (Kotlin, Jetpack Compose, Material 3; `minSdk 26`, `targetSdk 34`) that assesses a device, monitors CPU/RAM live, lets you close or freeze apps, and – on a **rooted** device – applies safe, reversible optimisations with a full backup/restore safety net.

> **It does not root your phone and contains no exploits.** Root must already be installed by you (Magisk or KernelSU). The app only *asks* the root manager for permission via `su`.

## Build

Requirements: JDK 17, Android SDK (platform 34, build-tools 34.0.0), Gradle 8.7+ (no Android Studio needed).

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk          # or put sdk.dir=... in local.properties
cd android-optimizer
gradle assembleDebug                               # or ./gradlew if you add the wrapper
# -> app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
(or copy the APK to the phone and open it; allow "install unknown apps" for your file manager). It is a debug-signed APK.

## Tabs

Bottom navigation: **Root · Assess · Monitor · Optimize · More** (More contains **Processes**, **Network**, **Diagnostics**, **Updates**, **Backups**, **Launchers**, **Google Play**, **Settings**).

| Tab | What it does | Needs root? |
|---|---|---|
| Root | Detects `su` and runs `su -c id` when you press *Request root access* (this triggers the Magisk/KernelSU prompt). Honest guidance if not rooted. Prompts to create the **baseline backup** the first time root is granted. | – |
| Assess | Hardware (manufacturer, model, board, SoC via `Build.SOC_*` or `/proc/cpuinfo`, cores, ABIs, CPU min/max freq, RAM, storage, display, battery, sensors, GPU renderer via EGL), software (Android, SDK, patch, kernel, fingerprint, bootloader, SELinux, verified boot), and app assessment (user/system counts, version, targetSdk, install date, APK size, granted dangerous permissions; flags apps with ≥ 6 dangerous permissions or targetSdk < 29). Export/share report as text. | No (CPU freq / some props are only readable with root) |
| Monitor | Live every 2 s: CPU % (`/proc/stat` deltas; falls back to `top`), RAM used/total/available, line graphs + bars, sortable app list (RAM / CPU / recent / name), **Close**, **Keep closed**, **Blocked apps** (one-tap *Allow / Open*). | Partly – see below |
| Optimize | Reversible fixes, maintenance, Doze whitelist, vendor ROM helpers, profiles, change log with *Undo this* / *Revert everything to baseline*. **Locked until root + baseline backup exist.** | Yes |
| Backups | Create / list / restore (full or by category) / delete backups. | Yes (create/restore) |
| Launchers | Curated open-source launchers, default-home info, optional root switch to disable the stock launcher. | Only the stock-launcher switch |
| Google Play | Detects Play Store/GMS/GSF, explains the right variants, verifies certificates, installs in the right order. | Only root install / privileged install / GSF ID |

### Monitor – what works without root

Android (8+) hides other apps' processes, `/proc/stat`, and per-process stats from normal apps.

* **No root:** overall RAM (ActivityManager), CPU via `/proc/stat` if readable, else `top` header if the shell allows it, else "n/a". The app list uses **Usage Access** (`UsageStatsManager`; the app prompts you to grant it) and shows apps used in the last 6 h; per-app RAM/CPU are shown only where Android exposes them (usually only this app) and the UI says so.
* **Root:** per-app RAM = PSS from `dumpsys meminfo`, CPU from `top`, listing of all running processes.
* **Close:** without root `ActivityManager.killBackgroundProcesses` (permission `KILL_BACKGROUND_PROCESSES`; cannot stop foreground/other-UID-protected apps); with root `am force-stop <pkg>`.
* **Keep closed:** with root `pm disable-user --user 0 <pkg>` (fallback `pm suspend`), recorded in the change log and persisted; *Allow / Open* runs `pm enable` (or `pm unsuspend`) and launches the app. Without root it only adds the app to a *watch list*, opens its system settings page (Force stop) and says true permanent blocking needs root. An optional foreground service re-kills watch-list apps' background processes every 30 s.
* **Protected apps** can never be closed/blocked: system UI, settings, phone/dialer, SMS default, current home launcher, the launcher providing Recents, active keyboard, Google Play services/Store/GSF, WebView, package installer/permission controller, providers, and this app. A confirmation dialog is shown for every block.

## Backup and restore (A)

* The first time root is granted the app prompts to create a **baseline** backup. Until a baseline exists, the Optimize tab is locked and root changes (freeze, debloat, launcher disable, privileged install, maintenance) are refused.
* What is backed up (via root shell): `settings list system|secure|global`, animation scales, `pm list packages -d -e -u` + suspended flags parsed from `dumpsys package`, **runtime permission grants** (parsed from `dumpsys package`), `getprop` and `/system/build.prop`, user APK list (`pm list packages -f -3`), selected `/data/system` files where readable (`packages.list`, `package-restrictions.xml`, `settings_*.xml`, `runtime-permissions.xml`, `appops.xml`, `deviceidle.xml` – kept as a record; restore uses the commands, not file replacement), and a `manifest.json` with timestamp and device info.
* Optional **app-data tarballs** (`tar` of `/data/data/<pkg>`, user opt-in by package name) are written only to the exported folder; they are large and are **not** restored automatically.
* Storage: app-private storage (`files/backups/<id>/`) **and** an exported copy in `Documents/AndroidOptimizer/<id>/` (MediaStore on Android 10+, root `cp` as a fallback).
* Restore: choose categories – Settings (`settings put`), package states (`pm enable` / `pm disable-user`), permissions (`pm grant/revoke`). A safety backup is taken first. Keys that could lock you out (provisioning, ADB, default input method, accessibility, Wi-Fi, lock settings, airplane mode, …) are never written back. Protected packages are never disabled by a restore.
* Delete removes the app copy and exported copy; the baseline cannot be deleted.
* Every Optimize action is logged with the exact undo commands; each entry has **Undo this**; **Revert everything to baseline** undoes the whole log (newest first) and then restores settings and package states from the baseline.

## Optimize tab (B)

Each item shows description, risk level and a toggle; confirmation dialogs are required and each change is logged.

* Animation scales 0.5x / off (`settings put global *_scale`).
* Disable the Android 12+ phantom-process killer (`device_config` + `settings_enable_monitor_phantom_procs`).
* Cap cached background processes (`activity_manager_constants max_cached_processes=16`; old value restored on undo).
* Force GPU rendering (`persist.sys.ui.hw`; ignored by some Android versions/ROMs).
* Maintenance (one-shot, not undoable): `pm trim-caches`, `cmd package bg-dexopt-job`, `cmd package compile -m speed-profile -a`, `fstrim -v /data` & `/cache`.
* Doze / battery helpers: exempt this app or chosen apps (`dumpsys deviceidle whitelist +pkg`, `cmd appops set pkg RUN_IN_BACKGROUND allow`, previous mode recorded).
* **Vendor ROM detection** (Build.MANUFACTURER and props such as `ro.miui.ui.version.name`, `ro.mi.os.version.name`, `ro.build.version.emui`, `BuildEx.getOsBrand` for HarmonyOS, `ro.build.version.opporom/oplusrom/oneplusrom`, Realme UI via `Build.DISPLAY`, `ro.vivo.os.version`, `ro.zui.version`, Blackview/Teclast/Chuwi/Alldocube by brand, Allwinner/Rockchip/MediaTek by `ro.board.platform`). For the detected vendor: notes (MIUI optimisation, Memory extension – informational only, never toggled), shortcuts to the vendor's own autostart / battery-killer screens (shown only if the screen resolves on the device) and an **optional debloat list** limited to packages that are actually installed. Debloat only ever runs `pm disable-user --user 0` (never uninstall) and is reversible. Package names were cross-checked against the "Recommended" removal entries of the [Universal Android Debloater](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation) list (`uad_lists.json`) at build time; entries where I was unsure carry a *VERIFY* note, and names not in that list (e.g. a Huawei ads package) were left out. For Teclast/Chuwi/Alldocube and generic SoC ROMs no list is offered (nothing verifiable).
* **Apply recommended profile** (animation 0.5x + Doze exemption for this app + clear caches) and **Undo all**.

## Launchers (F)

Curated and verified (GitHub repo API, F-Droid / IzzyOnDroid pages) – Lawnchair, Neo Launcher, Kvaesitso, Olauncher, Fossify Launcher, Trebuchet (LineageOS). Each card has description, license, F-Droid/IzzyOnDroid + GitHub buttons, installed detection (PackageManager), Launch, and a *Set default home app* button (`Settings.ACTION_HOME_SETTINGS`). Niagara Launcher is **not** listed (closed source); "Simple Launcher" is represented by its maintained successor Fossify Launcher. The current default home app is shown.

With root, a switch can disable a pre-installed launcher (`pm disable-user --user 0`) **only if** another launcher is the default, another enabled home app remains, the target does not provide the Recents screen, and the baseline exists. After disabling, the app re-resolves the home intent and **auto-reverts** if no usable home app remains. Logged and reversible (also a *Re-enable* button).

## Google Play installer (J)

* Detection: Play Store, Play services, GSF (installed + version), `Build.SUPPORTED_ABIS`, SDK, DPI bucket, ROM/vendor, HMS presence; a plain-language explanation of which variant to pick and why.
* **No APKs are bundled.** Source: **APKMirror** (Google-signed builds). APKMirror has no API or stable direct links, so the app deep-links the correct APKMirror page in the browser; you download, then pick the file in the app (plain `.apk`, or an `.apkm/.xapk/.apks` bundle containing `.apk` files). You can alternatively paste a direct **https** link from a source you trust – cleartext is refused.
* **Certificate pinning:** before install, the signer SHA-256 of every APK is computed (`PackageManager.getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES`, which also validates the APK signature) and compared with the allow-lists below. If it doesn't match, install is **refused** and the fingerprint is displayed.
  * `com.android.vending`: `F0:FD:6C:5B:41:0F:25:CB:25:C3:B5:33:46:C8:97:2F:AE:30:F8:EE:74:11:DF:91:04:80:AD:6B:2D:60:DB:83` – read live from Google's `https://play.google.com/.well-known/assetlinks.json`.
  * `com.google.android.gms`: the six fingerprints Google publishes in `https://www.google.com/.well-known/assetlinks.json` (`F0:FD:6C:5B…DB:83`, `19:75:B2:F1…FA:00`, `7C:E8:3C:1B…50:53`, `D2:2C:C5:00…76:78`, `5F:23:91:27…54:02`, `8F:D4:A0:E7…0A:36`; full values are in `Play.kt`) – read live from that file.
  * `com.google.android.gsf`: Google does not publish this in assetlinks. `F0:FD:6C…` was confirmed from a third-party analysis of a GSF APK (Pithus report) and `7C:E8:3C…` from the GrapheneOS AppVerifier discussion; both are Google keys. Treated as *community-verified*.
  * APKMirror notes some Google apps (including Play Store and GSF) have a second valid signing key. Those APKs are **refused** because I could not verify the full SHA-256 of the second key; use the other variant, or verify yourself.
* Order: GSF → Play services → Play Store. Installed by `PackageInstaller` session (`REQUEST_INSTALL_PACKAGES`; you confirm the system dialog) or, with root, `pm install -r`.
* **Advanced privileged install** (root + baseline, opt-in): copies the APK to `/system/priv-app/<Name>/`, generates `privapp-permissions-aopt-<pkg>.xml` from the permissions the APK requests that are privileged on your device, sets `root:root`, mode 644/755 and the `system_file` SELinux context, handles `mount -o rw,remount` and re-mounts read-only afterwards. It is logged with an undo (removes the files). **On most modern devices `/system` can't be remounted read-write (dynamic partitions / erofs / dm-verity)** – then nothing is changed and you're told so. A systemless Magisk module is the usual route and is not provided.
* **"Device is not certified":** *Read GSF ID* (root) runs `sqlite3 /data/data/com.google.android.gsf/databases/gservices.db "select * from main where name='android_id'"` (falls back to the GSF content provider), shows decimal and the 16-digit hex and opens `https://www.google.com/android/uncertified/`. The GSF database only exists after GSF has run.
* Honest limitations: Huawei/HMS devices usually can't run GMS at all; Chinese-market ROMs may kill GMS in the background (see Optimize › vendor fixes); Google's ToS may forbid this on uncertified devices; Play Store may remain broken on some ROMs even after a clean install. `com.google.android.gsf.login` (Google Account Manager) is only needed on Android ≤ 7 per community guidance and is not offered (minSdk is 26).

## Theme (D)

More › Settings: **System / Light / Dark** (Material 3 light and dark colour schemes) and an **AMOLED black** switch (pure-black backgrounds in dark mode). Choices persist in SharedPreferences and apply immediately; `values-night` theme avoids a white window flash. No root.

## Updates (E)  – More › Updates

* **Outdated apps (heuristic).** Android exposes no "update available" API to third-party apps, so the list flags user apps not updated for > 365 days or whose targetSdk is ≥ 4 levels below the device. Each has *Open in Play* (`market://`, https fallback) and *App info*. Play is the authority on whether an update really exists.
* **System update helper.** Shows Android version / patch age, opens the system update screen (`android.settings.SYSTEM_UPDATE_SETTINGS`) and vendor updater activities that resolve on the device. It cannot detect pending OTAs.
* **Disable automatic OTA (root + baseline, reversible).** Sets `Settings.Global ota_disable_automatic_update=1` (undo restores the previous value). ROMs may ignore it. Optionally `pm disable-user` on a known vendor updater package – flagged HIGH risk with confirmation; Xiaomi `com.android.updater` and ColorOS `com.coloros.sau` are intentionally not offered because disabling them is reported to cause boot problems.
* **Chinese-ROM update fixes / Play repair (root, confirmations).** *Clear cache* force-stops the app and removes cache directories (updaters, Play Store, Play services, GSF); *Clear data* (`pm clear`, baseline required) is irreversible and warned. Without root the buttons open the app-info screen instead.
* **WebView**: shows the current provider, links to WebView in Play and to the WebView implementation setting.
* **Uncertified device**: explanation and link to google.com/android/uncertified (GSF ID reading is in the Google Play screen).

## Diagnostics / conflicts (G) – More › Diagnostics

On-demand scan (+ optional **WorkManager** periodic scan, 6/12/24 h, with a notification; the background run never asks for root, so root-only checks are skipped). ~19 checks on real device data: low storage / RAM, animation scales, security-patch age, SELinux, unlocked bootloader, ADB enabled, no/multiple default launchers, multiple IMEs, accessibility services, task-killer apps, battery-optimisation exemptions, vendor killers, disabled Play/GMS, blocklist drift, phantom-process limit, old targetSdk apps, sideloaded apps, device admins. Every finding shows **severity, explanation, evidence** and one or more **fix options** (checkboxes). *Fix selected* / *Fix all recommended* first show a **preview of the exact commands**; execution needs root + baseline, is written to the change log and is undoable. Options that are only deep-links (Settings screens) work without root. Findings are heuristics: a finding is a reason to look, not proof of a fault.

## Processes (H) – More › Processes

* Lists processes from `ps -A` (package, UID, RSS RAM, CPU, start time, owner). **Without root Android only shows the app's own processes** (stated in the UI). CPU is the **lifetime average** from `ps`, not a live value.
* **Suspicion score** (Low / Medium / High) is a transparent sum of factors shown under *Why?*: accessibility service, device admin, notification-listener, sensitive permissions, draw-over-apps, no launcher icon, boot receiver, unknown installer, system-sounding name from a non-system app, high CPU/RAM, old targetSdk, tracker-list or beacon hits from the Network tab. It is **not an antivirus verdict** and says so in the UI.
* **Critical whitelist**: system UID, `Protected` packages (System UI, launcher, keyboard, Play/GMS, dialer…) can't be selected.
* **Stop / Ban / Unban** with multi-select and confirmation. Ban (root + baseline) = force-stop + `pm disable-user`, logged and undoable; Unban re-enables.
* **Stock snapshot & restore (root)**: JSON with processes, package enabled states, runtime permissions, app-ops, enabled accessibility services, notification listeners, device admins and network blocks, stored in app storage. Restore is per category and logged (the restore itself is not auto-undoable; take a new snapshot first if unsure). Restore of device-admin state only removes admins (re-activating requires user consent in Settings); app-op/permission restores are best-effort.

## Network (I) – More › Network

* **Live connections** per app from `/proc/net/tcp,tcp6,udp,udp6` (UID → package). **Needs root** (Android 10+ blocks these files). Flags: tracker-list match, raw IP with no reverse DNS, uncommon port, probable cleartext (port 80/8080/… or manifest `usesCleartextTraffic`). The app cannot see hostnames requested, payloads or SNI.
* **Sampler** (optional foreground service, root): stores new connections in a local SQLite DB (never leaves the device) with configurable interval and retention; short-lived connections between samples are missed.
* **Beaconing analysis**: ≥ 6 connection starts per app+endpoint with interval jitter ≤ 25 %, median 5 s – 4 h. Legit push/analytics also beacon; it's a lead.
* **Per-app timeline**: 24 h bar chart (30-min buckets) + list.
* **Tracker blocklist**: HaGeZi DNS Blocklists vendor "native tracker" lists (Xiaomi, Huawei, OPPO/Realme, …) – **GPL-3.0**, downloaded at runtime (not bundled) from a **pinned commit** over HTTPS, **SHA-256 verified** (mismatch ⇒ refused). Because the app sees remote IPs, domains are forward-resolved to IPs and reverse DNS is also tried: matching is **best effort** (CDN IPs change). The pinned lists will age; change `Trackers.COMMIT` and hashes to update. Disconnect.me was rejected (license).
* **Network block (root + baseline, reversible)**: `iptables`/`ip6tables -m owner --uid-owner UID -j REJECT` (rules are lost on reboot → *Re-apply*) or `cmd netpolicy add restrict-background-blacklist UID` (background data only). Recorded in the change log and in the snapshot; protected apps can't be blocked.

## Limitations / things you must verify on your device

* **This build was only compiled.** It could not be run in an emulator on the build machine (no KVM permission), so no UI or runtime testing was done. Behaviour on real hardware, on different ROMs and under Magisk vs KernelSU hasn't been verified by the author.
* Root command output formats differ between Android versions and vendors (`dumpsys`, `top`, `pm`). Parsers are best-effort; failures show as "n/a" or an error message.
* Settings restore is deliberately conservative. Some values (secure/global) may need a reboot or are re-written by the system.
* `device_config` values may be reset by the system. `persist.sys.ui.hw`, `activity_manager_constants` and vendor-specific effects can differ per ROM – these are marked Medium risk.
* Vendor autostart screen component names change between ROM versions; a button only appears if the component resolves on your device.
* Debloat candidate lists were cross-checked against a community list, but ROM updates change packages. Review each entry; disabled packages can be re-enabled from the change log or with `pm enable <pkg>` over ADB.
* The `Documents/AndroidOptimizer` export uses MediaStore (Android 10+); on Android 8–9 it uses root `cp` to `/sdcard`.
* Debug-signed, not for Play Store distribution. `QUERY_ALL_PACKAGES` and `REQUEST_INSTALL_PACKAGES` would require policy justification on Google Play.

## Project layout

```
app/src/main/java/dev/optimizer/android/
  MainActivity.kt                  navigation (bottom bar)
  core/    Sh.kt (shell + root), Store.kt, ChangeLog.kt, Backup.kt, Protected.kt
  features/ Monitor.kt, Assessment.kt, Optimize.kt, Vendor.kt, Launchers.kt, Play.kt, AutoKillService.kt,
            Updates.kt, Diagnostics.kt (+DiagWorker), Processes.kt (+Snapshots), Network.kt (+NetDb, sampler service, Trackers)
  ui/      Compose screens
```
