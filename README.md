# TyphoonEye

[![Build Android APK](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/actions/workflows/build-apk.yml/badge.svg)](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/actions/workflows/build-apk.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![Android SDK](https://img.shields.io/badge/API-29%2B-3DDC84.svg?style=flat&logo=android)](https://developer.android.com/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![F-Droid](https://img.shields.io/badge/F--Droid-metadata-1976D2.svg?style=flat)](metadata/seamain.org.typhoonEye.yml)

English | [中文](README_CN.md)

**TyphoonEye** is an open-source Android app for **northwest Pacific typhoon** tracking: interactive maps, multi-quadrant wind radii, forecast paths, and optional location-based alerts.

Built with **Jetpack Compose** and **Material Design 3**.

> **Disclaimer:** TyphoonEye is a third-party client for public weather data. It is **not** an official product of any meteorological agency. Always follow guidance from local authorities in emergencies.

---

## Features

- Interactive **MapLibre** map: history track, forecast track, 7 / 10 / 12-level wind radii by quadrant
- Typhoon detail: pressure, wind, movement, observation timeline
- Optional **distance-based emergency alerts** (location permission)
- Live status notification + background refresh (`WorkManager`)
- Material 3, dynamic color, light / dark theme
- Languages: Simplified Chinese, Traditional Chinese, Cantonese, English
- **F-Droid build:** live typhoon data right after install, fetched through TyphoonEye's own relay server — no API key in the app, no location sent (see [Data sources](#data-sources))
- Last fetched data is cached for offline use; a clearly labeled **demo mode** (fictional storms) is offered only when live data can't be loaded and nothing is cached
- Two distribution flavors:
  - `github` — in-app update from GitHub Releases
  - `fdroid` — updates via F-Droid only (no sideload installer)

---

## Tech stack

| Area | Stack |
|------|--------|
| UI | Jetpack Compose, Material 3, Navigation |
| Map | [MapLibre Native Android](https://github.com/maplibre/maplibre-native) |
| DI | Hilt |
| Network | Retrofit, OkHttp, optional QWeather JWT (Ed25519) |
| Storage | Room, DataStore |
| Background | WorkManager |
| CI | GitHub Actions |

Architecture: **MVVM + clean-ish layering** (UI → ViewModel → repository → remote/local).

---

## Requirements

- Android Studio Ladybug (2024.2+) or newer  
- JDK **21**  
- Android SDK **36** (minSdk **29**)

---

## Build

```bash
git clone https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid.git
cd TyphoonEyeAndroid
```

### Optional API keys (`github` flavor only)

Copy the example file and fill only what you need:

```bash
cp local.properties.example local.properties
```

| Key | Purpose |
|-----|---------|
| `JUHE_KEY` | Juhe typhoon list / detail (optional) |
| `QWEATHER_API_KEY` or JWT fields | QWeather typhoon + warnings (optional) |
| `AMAP_KEY` | Amap raster basemap in mainland China (optional) |

These keys are used only by the `github` flavor, which calls the providers directly. The `fdroid`
flavor ignores them (it is always built without keys) and talks to the TyphoonEye relay instead.

A `github` build without any key installs and runs, but cannot show live typhoon data: it says no
live data source is configured and offers an optional, clearly labeled **demo mode** (fictional sample
storms, marked "Sample data — not real typhoons").

See [local.properties.example](local.properties.example) and [PRIVACY.md](PRIVACY.md).

### Gradle targets

```bash
# Default GitHub channel (debug)
./gradlew assembleGithubDebug

# Release (GitHub channel, signed if keystore is configured)
./gradlew assembleGithubRelease

# F-Droid channel (no GitHub in-app updates)
./gradlew assembleFdroidRelease
```

### Building locally

Two scripts build the release editions the same way CI does (JDK 21, Android SDK required):

| Script | Edition | Allowed on | Output |
|--------|---------|------------|--------|
| `scripts/build-github.sh` | GitHub (universal + per-ABI, built-in keys) | `master` or a `v*` tag | `dist/github/TyphoonEye-v<ver>-app-github-release.apk`, `…-app-github-split-<abi>-release.apk` |
| `scripts/build-foss.sh` | F-Droid (per-ABI, no keys) | `foss` or an `fdroid-*` tag, clean tree | `dist/foss/app-fdroid-<abi>-release-signed.apk` |

Both run the flavor's unit tests, check every APK's versionCode and signing certificate, and write
`SHA256SUMS` (with the commit hash). Options: `--skip-tests`, `--debug` (debug-signed, written to
`dist/*-debug/`, no signing checks), `--force` (other branch; the GitHub script also accepts a dirty
tree), `-h`. `build-foss.sh --compare <dir>` compares with CI/F-Droid APKs via `apksigcopier`.

`local.properties` (see [local.properties.example](local.properties.example)) needs `sdk.dir` (or
`ANDROID_HOME`) and, for release builds, `RELEASE_STORE_FILE` (or `./release.keystore`),
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` (or the same environment
variables). A missing keystore is an error, never a silent debug-key build. The GitHub edition also
uses the data-source keys. `build-foss.sh` never modifies your checkout: it builds the committed HEAD
in a temporary `git worktree` with only `sdk.dir` and the signing entries, and fails if any locally
configured key value is found in the APKs. Use Temurin 21.0.12 (as CI / F-Droid) for byte-identical
F-Droid APKs.

### Versioning

Release numbers live in [`version.properties`](version.properties):

```properties
VERSION_NAME=1.2.0
VERSION_CODE=15
```

CI may override via `VERSION_NAME` / `VERSION_CODE` env vars. Tag releases as `v1.2.0`.

---

## Data sources

| Build | Typhoon data | API keys | Official warnings |
|-------|--------------|----------|-------------------|
| `fdroid` | Through the TyphoonEye relay `https://te-relay.seamain.org` (Cloudflare Worker, source in [`relay/`](relay/)) | None in the app; Worker secrets only | Relay fetches warnings for a fixed list of coastal cities; the app downloads the shared list and filters by distance **on the device**. Coastal areas only: more than 150 km from every listed city, only the app's own intensity alerts |
| `github` | Directly from Juhe / QWeather | Built in at build time (CI secrets / `local.properties`) | Queried from QWeather at the device's approximate position (if location is allowed) |

The relay forwards only the calls the app needs (typhoon list / detail from Juhe; storm list, track and
forecast from QWeather), returns the providers' JSON unchanged and caches it (lists 10 min, details
30 min). It uses the client IP only for rate limiting and does not store it; it keeps no request logs.
Requests to it pass through Cloudflare. Deployment: [relay/README.md](relay/README.md).

In the F-Droid build, official warnings near you therefore cover **coastal areas only**
(54 fixed points along the coasts of mainland China, Hong Kong, Macau, Taiwan and nearby
countries; a point applies within 150 km). Farther inland the app shows only its own
intensity alerts for nearby or very strong storms.

---

## Releases & CI

Workflow: [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)

- Push / PR → build artifacts  
- Tag `v*` → GitHub Release + `github` flavor APK  

Secrets used by CI (repository settings): API keys (optional), release keystore (optional).

---

## F-Droid

- App source remains on **GitHub**
- Packaging draft: [`metadata/seamain.org.typhoonEye.yml`](metadata/seamain.org.typhoonEye.yml)
- Submit packaging via GitLab **[fdroiddata](https://gitlab.com/fdroid/fdroiddata)** MR (metadata only)
- Flavor: `fdroid` · AntiFeature: `NonFreeNet` (TyphoonEye relay → third-party weather APIs; optional Amap tiles)
- The F-Droid build contains no API keys; `relay/` is not part of the Gradle build

Checklist (CN): [README_CN.md](README_CN.md#f-droid-投稿说明)

---

## Privacy

- No ads, no analytics SDKs  
- Location is used **only** for optional distance alerts and is not uploaded to a TyphoonEye server  
- F-Droid build: typhoon data requests go through Cloudflare and the TyphoonEye relay; the relay uses your IP only for rate limiting and does not store it; your location is never sent to it  
- GitHub build: weather requests go directly to the third-party APIs built into it  

Full text: [PRIVACY.md](PRIVACY.md)

---

## Contributing

Issues and pull requests are welcome.

1. Fork + branch from `foss` or `master`  
2. Keep changes focused; match existing style  
3. Do **not** commit `local.properties`, keystores, or real API keys  
4. Prefer `./gradlew test` before opening a PR  

---

## License

[Apache License 2.0](LICENSE)

Map data/providers and weather APIs remain under their own terms.
