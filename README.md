# Android Optimizer

A native Android app (Kotlin, Jetpack Compose, Material 3; `minSdk 21` (Android 5.0), `targetSdk 34`, version 1.5.0) that assesses a device, monitors CPU/RAM live, lets you close or freeze apps, and – with root (Rooted edition), Shizuku or a few adb grants – applies safe, reversible optimisations with a full backup/restore safety net. It ships as **two editions** (see *Editions*).

> **It does not root your phone and contains no exploits.** In the Rooted edition root must already be installed by you (Magisk or KernelSU); the app only *asks* the root manager for permission via `su`. The Standard edition never calls `su` at all.

## Build

Requirements: JDK 17, Android SDK (platform 34, build-tools 34.0.0), Gradle 8.7+ (no Android Studio needed).

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk          # or put sdk.dir=... in local.properties
cd android-optimizer
gradle assembleRootedDebug assembleStandardDebug assembleRootedRelease assembleStandardRelease
# -> app/build/outputs/apk/rooted/{debug,release}/app-rooted-{debug,release}.apk
# -> app/build/outputs/apk/standard/{debug,release}/app-standard-{debug,release}.apk   (release = universal, R8-minified, signed)
```

**Release signing.** `app/build.gradle` reads `/workspace/keystore/keystore.properties` (override with `-PkeystoreProps=<file>` or the env var `KEYSTORE_PROPS`); if the file is missing the release APK is produced unsigned. The generated keystore, its properties and a note with the credentials live in `/workspace/keystore/` (see `/workspace/keystore/README.txt`). **Back this folder up** - an APK signed with a different key cannot update an installed one. Credentials are intentionally not repeated here.

**Universal APK.** ABI and density splits are disabled; one APK installs everywhere. The app has no native libraries of its own, so it is ABI-independent (arm, arm64, x86, x86_64 all run the same dex code).

## Install

```bash
adb install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk   # or the rooted one; both can be installed side by side
```
(or copy the APK to the phone and open it; allow "install unknown apps" for your file manager). The debug APK is debug-signed; the release APK is signed with the local keystore (a different key, so uninstall one before installing the other).

## Tabs

Bottom navigation (a navigation rail on screens ≥ 600 dp wide): **Root · Assess · Monitor · Optimize · More** (More contains **RAM Optimizer**, **Rules**, **Processes**, **Network**, **Diagnostics**, **Updates**, **Backups**, **Launchers**, **Google Play**, **Settings**).

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

## Compatibility (K): minSdk 21
* **minSdk 21** is the lowest the stack allows: Compose, Material 3, activity-compose, lifecycle 2.8 and WorkManager 2.9 all support API 21. Lower is not possible with this stack. **targetSdk stays 34** so that current devices do not apply legacy-compat behaviour; **note for Google Play:** Play requires a newer target API for new uploads (it moves every August) and the permissions `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES` and `PACKAGE_USAGE_STATS` need a policy declaration, so this build is for sideloading, not Play.
* Every newer API is behind `core/Compat.kt` (`SDK_INT` guards + fallbacks): notification channels (26+) via `NotificationCompat`, `startForegroundService`, foreground-service type `specialUse` (API 34 only; the 2-argument call below), `POST_NOTIFICATIONS` (33+), `getInstallSourceInfo` (30+), signature APIs (28+ / legacy), runtime permissions (23+), Doze (23+), `cmd` (24+), `appops`/`cmd appops`, `ps`/`top` toolbox variants for Android < 8, `iptables -w`, MediaStore export on 10+ vs app-specific storage / root `cp` below, FileProvider sharing.
* `uses-feature required=false` for touchscreen, multitouch, faketouch, telephony, camera, bluetooth(LE), wifi, location/gps, microphone, nfc, accelerometer, portrait/landscape, leanback and usb host, so the APK is installable on tablets, TV boxes, set-top boxes and phones without those parts. A TV banner and a `LEANBACK_LAUNCHER` entry are included; the UI is D-pad friendly because it uses standard Material components.
* **Responsive UI:** bottom bar on narrow screens, navigation rail on ≥ 600 dp, content capped at 960 dp, rotation handled without restarts.

## Device Profile & setup wizard (K)
On first launch (and after every OS update / security-patch change, detected from `Build.FINGERPRINT` + patch level) the app runs a **Device Profile** scan: Android/API, ABI(s), RAM class, storage, screen size/density, vendor and ROM, SoC family, Play/GMS/HMS, thermal and sensor availability, TV/touch, Usage Access and notification permission, and whether root was previously granted (the scan itself never triggers a root prompt). The result is stored as JSON, shown in **Assess › Device profile**, and written into every backup manifest (`device_profile`) and baseline.

The profile drives adaptation: **low-end mode** (RAM ≤ 2 GB or the low-RAM flag; override in Settings: Auto/On/Off) slows the Monitor refresh, shortens histories, lowers background sampling and disables heavy dexopt jobs; features that cannot work are explained in a "limits" note on the More cards (for example no `netpolicy` before Android 7, no phantom-process check before Android 12, Huawei HMS without GMS); vendor guidance and command variants are chosen per Android version. A **6-step setup wizard** asks for permissions only when needed and says why (Usage Access, notifications, root); it can be re-run from Settings, and **Re-scan device** is in Settings and Assess.

## Root Setup helper (L) - Root tab
Shows what the device reports: `su` presence, manager (Magisk / KernelSU / APatch / SuperSU / other), bootloader lock state (`ro.boot.flash.locked`, `ro.boot.verifiedbootstate`, OEM-unlock property), A/B slot, boot vs init_boot target, Android version, ABI and vendor. If the phone is not rooted it shows an honest guided flow: enable developer options and OEM unlocking → unlock the bootloader (**this wipes the data and may void the warranty**; some vendors need an unlock account/waiting period or do not allow it) → obtain the *stock* boot/init_boot image for your exact build → patch it with the Magisk app → `fastboot flash` the patched image to the detected slot. Notes cover MediaTek/Rockchip/Allwinner (SP Flash Tool, rkdeveloptool, PhoenixSuit), Xiaomi, Huawei and Samsung (Odin, Knox).
* **Copy commands** copies the adb/fastboot commands for your detected slot. **Download Magisk APK** only opens the official release page (https://github.com/topjohnwu/Magisk/releases); nothing is downloaded or bundled by the app. If a root manager is installed, an **Open manager** button launches it.
* Verified links (HTTP 200 when checked on 2026-10-16): Magisk releases + install guide, KernelSU repo + install guide, APatch repo + site, AOSP bootloader locking/unlocking, Xiaomi unlock page, Android platform-tools, rockchip-linux/rkdeveloptool.
* **Verify root** runs one `su -c id` check; a successful check offers to create the baseline backup.
* **Advanced - install `su` binary:** you pick a file yourself; the app shows its SHA-256, checks that it is an ELF for this ABI, and (root required, `/system` must be writable) copies it to `/system/xbin/su` or `/system/bin/su` with mode 06755 root:root and the SELinux context. It refuses if a `su` already exists, requires the baseline, logs the change and offers undo. The app never bundles or downloads a `su` binary. On modern Android with dm-verity / read-only system this is usually impossible; use Magisk/KernelSU instead.

## Detect root access (O) - top of the Root tab
One button runs a full detection with **a single root prompt**: `su` in common paths (`/system/bin`, `/system/xbin`, `/sbin`, `/vendor/bin`, `/debug_ramdisk`, `/data/adb/magisk`, `/data/adb/ksu`, `/data/adb/ap`, ...), `which su`, installed manager apps (known packages plus a component-name heuristic for renamed/stub managers), `ro.build.tags` test-keys, writable `/system`, and finally `su -c id` (success only if the output contains `uid=0`). Result cards: **Root detected and granted**, **Root found but access denied / not granted** (with an Open-manager button), **Root binary found but not working**, **No root found**. Each card lists pass/fail evidence and manager/version and has **Copy report**. Implementation: `features/RootDetect.kt`, `Root.probe()` in `core/Sh.kt`.
> (Update: a persistent root shell and health check now exist, see N below.) The request also said to reuse "the persistent root shell and the health check from N". No item N was ever supplied, so none was reused; the detector uses one self-contained `su -c <script>` call (a prompt appears at most once per detection). If N refers to existing code elsewhere, it can be wired in later.

## RAM Optimizer (P) - Monitor card and More › RAM Optimizer
* **Optimize RAM** always opens a preview: every candidate app with a checkbox (all ticked), RAM per app (PSS from `dumpsys meminfo`, root only), select all/none, and a live count and total. Nothing is closed without your confirmation.
* **Protected** (never listed): critical system packages, the launcher, the active keyboard, apps with an active foreground service / media session / call or navigation (root-based best effort via `dumpsys`; without root only the foreground app from Usage Access is known), the app you are using, this app, and your **Never stop** list.
* Actions: close background/cached processes (`am kill` gentle, optional `am force-stop`; without root `killBackgroundProcesses`), ask apps to trim memory (`am send-trim-memory`), drop caches (`sync; echo 3 > /proc/sys/vm/drop_caches`, flagged as a temporary slowdown), compact memory (`/proc/sys/vm/compact_memory`, only if the kernel has it).
* **Kernel tuning presets** (Balanced / Aggressive / Gaming): `swappiness`, `vfs_cache_pressure`, optional `min_free_kbytes` (expert), and **zRAM resize** (swapoff → reset → disksize → mkswap → swapon, refused when swap is in use). Current values are read first; there are sanity limits (swappiness 0-100, vfs 50-200, min_free_kbytes ±50% and ≤ 4% of RAM, zRAM 25-100% of RAM and ≥ 256 MB); values are runtime-only, saved in the baseline and logged with undo.
* **Before / after** (available, free, cached, swap) is shown after each run and kept in a history (50 entries).
* **Auto-optimize**: triggers on a low-available-RAM threshold, screen-off and/or a schedule (WorkManager), rate-limited (minimum interval and per-hour cap), posts a summary notification, default strength **Gentle**. Background runs never prompt for root.
* **Honest impact note:** Android already reclaims cached memory by itself. Closing apps frees RAM only briefly, forces cold restarts (slower launches, more CPU and battery) and can lose unsaved state. Gentle is recommended; Aggressive/Gaming are for specific situations.
* Without root: only `killBackgroundProcesses` works; no per-app RAM, no tuning. A card links to Developer options (Running services / Background process limit).
* Diagnostics has a **Memory pressure** check (available %, low-memory flag, swap use) with options: gentle optimize, compact (root), cap cached processes, Developer options, manual review.

## Per-app selection and rules (Q) - More › Rules
* Every close/stop list (RAM Optimizer preview, Monitor › Close apps…, Processes › Stop all…, Diagnostics fixes) has checkboxes (preselected), select all/none, and a count + estimated RAM total. Each app row has a **lock** (Never stop) and a **Keep closed** toggle.
* **Never stop** is enforced by one gate (`Rules.blockReason`) used by Monitor, Processes, RAM Optimizer, the auto-optimizer, the auto-kill service, the guard and bulk actions. **Keep closed** is enforced by the **guard service** (root: `logcat -b events am_proc_start` in one persistent root shell, polling as fallback; non-root: polling + `killBackgroundProcesses` from a foreground service). The two rules exclude each other (the UI refuses to set both; Never stop wins on import).
* Enforcement levels per rule: **Close on start**, **Background restrict** (root: app-ops `RUN_IN_BACKGROUND`/`RUN_ANY_IN_BACKGROUND` ignore, `deviceidle whitelist -pkg`, `am set-inactive`, standby bucket `restricted`) and **Freeze** (`pm disable-user`, fallback suspend). All logged in the change log and undoable.
* **Kill-loop protection:** more than 5 closes in 10 minutes pauses the rule for 30 minutes and a notification suggests Freeze. Notification actions: *Allow this time* (30 min), *Allow always* (removes the rule), *Keep blocked*, *Freeze*.
* Protected packages and the foreground app are never touched. Rules can be exported/imported as JSON and are included in backups ("App rules" category; kernel tunables are the "VM tunables" category).
* Without root the guard cannot stop foreground apps and Android may restart apps; Freeze and background restrict need root.


## Backup folder via SAF (M) and persistent root shell + health check (N)

* **M - Backup folder (SAF).** Backups screen › *Backup folder*: pick any folder with the system picker (`ACTION_OPEN_DOCUMENT_TREE`, persisted URI permission; no storage permission, works on Android 5+). New backups are copied to `<folder>/AndroidOptimizer/<id>/`; *Copy all backups now* and *Import from folder* (restores backups that are in the folder but not in the app, e.g. after reinstalling). If the folder is not set or copying fails, the older MediaStore / root `cp` / app-specific-storage export is used.
* **N - Persistent root shell + health check** (`core/RootShell.kt`). Rooted edition only: one reused `su` process (end-marker protocol, self-restart on hang) used by the live sampler. Root tab › *Root health check* reports shell answer/latency/restarts, SELinux, Magisk/KernelSU/APatch version, `/data/local/tmp` write, `pm` reachability. The Standard edition offers a passive-only check (su visible, manager app installed). Note: the original N/M specifications were not available to the implementer beyond these titles, so these are interpretations; the Root-detect button (O) still uses its own single `su -c` call.

## Editions (S)

Gradle `productFlavors`, dimension `edition`:

| | **Rooted** (`dev.optimizer.android`) | **Standard** (`dev.optimizer.android.standard`) |
|---|---|---|
| Launcher name | Android Optimizer | Optimizer Standard |
| Runs `su` | yes (root > Shizuku > ADB grants > plain) | **never** (Shizuku > ADB grants > plain) |
| Root tab / Root setup helper / Detect root / su install | yes | replaced by the **Access** tab (Shizuku, adb grants, wireless-debugging guide, capability matrix, root-lost help) |
| Close apps, RAM Optimizer | force-stop, trim, drop caches, compact | `killBackgroundProcesses`; force-stop via Shizuku or optional Accessibility service; drop caches / compact need uid 0 (not available) |
| Keep closed | freeze (`pm disable-user`/suspend) + event guard | UsageStats watcher + kill; freeze/suspend only with Shizuku |
| Freeze / disable apps, startup-receiver disable | yes | only with Shizuku |
| Kernel tuning, zRAM, swappiness | yes | **no** (needs real root) |
| Settings tweaks (animation scales etc.) | yes | with `WRITE_SECURE_SETTINGS` (one adb command) or Shizuku; otherwise guided shortcuts to Developer options |
| Backups | full (settings, packages, system files) | what the app can read without root (Settings values, package list/states) |
| Network | iptables / netpolicy block, /proc/net connections | opt-in local **VPN firewall** (one VPN at a time), per-app usage via NetworkStatsManager; netpolicy with Shizuku; no live /proc/net on Android 10+ |
| Monitor, widgets, storage cleaner, battery, crash viewer, Freeze Doctor, assessment, Play installer | yes | yes, with less evidence (no dumpsys/logcat/dmesg unless Shizuku or DUMP/READ_LOGS granted) |

Both editions are built from the same code: `BuildConfig.ROOT_EDITION` selects the edition and every privileged command goes through one `Priv.exec` command runner (`core/Priv.kt`). If `su` is not allowed (Standard) or not granted, the runner falls back to Shizuku, then to a Settings-API shim (needs `WRITE_SECURE_SETTINGS`), then to a read-only allow-list run as the app's own user; anything else returns "needs root or Shizuku" instead of failing silently.

**Capability matrix.** Access tab (Standard) / More › Access & capabilities (Rooted): every feature is shown as *Works*, *Limited (needs Shizuku/ADB)* or *Needs root (unavailable)* for root, Shizuku and plain mode, with your current state marked and the unlock steps. Features upgrade automatically when Shizuku is detected.

**Shizuku (optional).** Open-source (RikkaApps, https://shizuku.rikka.app/). Install it, start it (Wireless debugging on Android 11+, or `adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`), then tap *Authorise this app*. The app talks to it through a small `UserService`. Shizuku started without root stops at reboot.

**One-time adb grants (no Shizuku needed)** - the Access tab has a *Copy commands* button:
```
adb shell pm grant dev.optimizer.android.standard android.permission.WRITE_SECURE_SETTINGS
adb shell pm grant dev.optimizer.android.standard android.permission.DUMP
adb shell pm grant dev.optimizer.android.standard android.permission.READ_LOGS
adb shell appops set dev.optimizer.android.standard GET_USAGE_STATS allow
adb shell appops set dev.optimizer.android.standard WRITE_SETTINGS allow
```
(Use `dev.optimizer.android` for the Rooted edition.) Wireless debugging (Android 11+): Developer options › Wireless debugging › *Pair device with pairing code*, then `adb pair <ip>:<port>`, `adb connect <ip>:<port>`.

**Root lost?** An OTA update or firmware flash replaces the boot image that Magisk patched. The Access tab explains Magisk's *Install to inactive slot (after OTA)* and re-patching (https://topjohnwu.github.io/Magisk/install.html) and, if an `su` binary or root manager becomes visible, offers a *Switch to Rooted edition* button (https://github.com/geek4945-creator/android-optimizer/releases). The check is passive (file/package look-up); the Standard edition never executes `su`.

**Opt-in helpers (Standard and Rooted).** *Accessibility force-stop*: only acts when you press "Force stop (accessibility)" in Monitor; it opens App info and taps the system button (text matching differs per ROM). *Local VPN firewall*: blocks the apps you choose by routing only them into a local VPN that drops their packets (no per-app connection log; replaces any other VPN while active).

## Widgets, Freeze Doctor and extras (R)

* **Widgets** (Small, Medium, Large; resizable; follow dark/light mode). Large: live CPU and RAM graphs, per-core CPU where readable, swap, storage, battery, temperature, uptime, network rate, top-5 RAM apps, and *Optimize RAM / Open / Refresh* buttons. Refresh runs from a lightweight foreground service every 1 / 2 / 5 s while the screen is on and is throttled when it is off (More › Quick tools). Android limits `updatePeriodMillis` to 30 minutes, so second-level refresh needs that service. Removing the last widget stops the service.
* **Freeze Doctor** (More › Freeze Doctor): probes pressure-stall info, page-fault/swap rates, disk I/O utilisation, eMMC life/EOL registers, last fstrim, thermal and CPU governor, top CPU/wakelock offenders, caches, boot receivers, battery, WebView, dexopt state, crash/dropbox buffers and kernel log; ranks causes with evidence and likelihood; selectable fixes with preview, undo and *Run recommended fixes*; health-snapshot export; hints for TankPro / MediaTek / Rockchip / Allwinner tablets. Without privileges it works with less evidence.
* **Extras**: Quick Fix wizard, Safe Boost (one undo), persistent RAM/CPU notification, scheduled maintenance (WorkManager, charging + idle), storage cleaner (moves to a restorable trash), battery health + temperature chart, startup-app manager, crash/ANR viewer, app-usage ranking, Reset to stock, Help/FAQ/About with a link to the releases page (no automatic update download).

## Limitations / things you must verify on your device

* **This build was only compiled.** It could not be run in an emulator on the build machine (no KVM permission), so no UI or runtime testing was done. Behaviour on real hardware, on different ROMs and under Magisk vs KernelSU hasn't been verified by the author.
* Root command output formats differ between Android versions and vendors (`dumpsys`, `top`, `pm`). Parsers are best-effort; failures show as "n/a" or an error message.
* Settings restore is deliberately conservative. Some values (secure/global) may need a reboot or are re-written by the system.
* `device_config` values may be reset by the system. `persist.sys.ui.hw`, `activity_manager_constants` and vendor-specific effects can differ per ROM – these are marked Medium risk.
* Vendor autostart screen component names change between ROM versions; a button only appears if the component resolves on your device.
* Debloat candidate lists were cross-checked against a community list, but ROM updates change packages. Review each entry; disabled packages can be re-enabled from the change log or with `pm enable <pkg>` over ADB.
* The `Documents/AndroidOptimizer` export uses MediaStore (Android 10+); on Android 8–9 it uses root `cp` to `/sdcard`.
* Not for Play Store distribution (see the Play note above). The release build uses R8 with conservative keep rules (the app's own package is kept); it was **not run on a device**, so a runtime problem caused by shrinking cannot be excluded - the debug APK is the fallback.
* Nothing was tested on Android 5-7 or on any real device; command variants for old versions are best effort.
* Foreground-service / media-session detection (RAM protection) needs dumpsys (root, Shizuku or DUMP) and varies by ROM.
* Not run on any device or emulator: Shizuku binding, the accessibility force-stop, the VPN firewall, widgets on real launchers, the R8 release build at runtime, Freeze Doctor parsers and old-Android command variants are compile-checked only.
* Some Standard-edition labels still say "root" where they mean "a privileged shell (root or Shizuku)".
* Standard edition cannot: change kernel parameters, zRAM or drop caches; freeze apps without Shizuku; read other apps' connections on Android 10+; clear other apps' caches; copy /data/system or app data in backups.

## Project layout

```
app/src/main/java/dev/optimizer/android/
  MainActivity.kt                  navigation (bottom bar / rail), profile scan, wizard
  core/    Sh.kt (shell + root probe), Store.kt, ChangeLog.kt, Backup.kt, Protected.kt, Compat.kt, DeviceProfile.kt, Rules.kt, Priv.kt (command runner, Shizuku bridge)
  features/ Monitor.kt, Assessment.kt, Optimize.kt, Vendor.kt, Launchers.kt, Play.kt, AutoKillService.kt,
            Updates.kt, Diagnostics.kt (+DiagWorker), Processes.kt (+Snapshots), Network.kt (+NetDb, sampler service, Trackers),
            Features.kt, RootDetect.kt, Guard.kt (+Enforce, GuardService), RamOptimizer.kt (+RamWorker, RamAutoService),
            Live.kt (widgets + LiveService), FreezeDoctor.kt, Cleaner.kt, Extras.kt, NoRoot.kt (accessibility force-stop, VPN firewall, NetUsage)
  ui/      Compose screens (SetupWizard, RootSetupUi, RamScreen, RulesScreen, CloseList, AccessScreen ...)
```
