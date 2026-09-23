# TyphoonEye

[![Build Android APK](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/actions/workflows/build-apk.yml/badge.svg)](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/actions/workflows/build-apk.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![Android SDK](https://img.shields.io/badge/API-29%2B-3DDC84.svg?style=flat&logo=android)](https://developer.android.com/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

English | [中文](README_CN.md)

**TyphoonEye** is an open-source Android app for **northwest Pacific typhoon** tracking: interactive maps, multi-quadrant wind radii, forecast paths, and optional location-based alerts.

Built with **Jetpack Compose** and **Material Design 3**.

This `master` branch is the **GitHub edition**: Play services location and in-app updates from GitHub Releases.

> **Disclaimer:** TyphoonEye is a third-party client for public weather data. It is **not** an official product of any meteorological agency. Always follow guidance from local authorities in emergencies.

---

## Features

- Interactive **MapLibre** map: history track, forecast track, 7 / 10 / 12-level wind radii by quadrant
- Typhoon detail: pressure, wind, movement, observation timeline
- Optional **distance-based emergency alerts** (location permission)
- Live status notification + background refresh (`WorkManager`)
- Material 3, dynamic color, light / dark theme
- Languages: Simplified Chinese, Traditional Chinese, Cantonese, English
- **Demo / offline cache** when API keys are not configured
- In-app update from GitHub Releases

---

## Tech stack

| Area | Stack |
|------|--------|
| UI | Jetpack Compose, Material 3, Navigation |
| Map | [MapLibre Native Android](https://github.com/maplibre/maplibre-native) |
| Location | Google Play services Location |
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

### Optional API keys

Copy the example file and fill only what you need:

```bash
cp local.properties.example local.properties
```

| Key | Purpose |
|-----|---------|
| `JUHE_KEY` | Juhe typhoon list / detail (optional) |
| `QWEATHER_API_KEY` or JWT fields | QWeather typhoon + warnings (optional) |
| `AMAP_KEY` | Amap raster basemap in mainland China (optional) |

Without keys the app still installs and runs with **demo / cached** data.

See [local.properties.example](local.properties.example) and [PRIVACY.md](PRIVACY.md).

### Gradle targets

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

### Versioning

Release numbers live in [`version.properties`](version.properties):

```properties
VERSION_NAME=1.2.0
VERSION_CODE=15
```

CI may override via `VERSION_NAME` / `VERSION_CODE` env vars.

---

## Releases & CI

Workflow: [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)

- Push / PR on `master` → build artifacts  
- Official tagged GitHub Releases (`v*`) are cut from the [`foss`](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/tree/foss) branch so they stay aligned with F-Droid  

Do **not** push a `v*` tag from `master` unless you intend to replace that release.

Secrets used by CI (repository settings): API keys (optional), release keystore (optional).

---

## F-Droid

F-Droid packaging (no Play services, no in-app sideload updates) lives on the [`foss`](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/tree/foss) branch. Application source stays on GitHub; only metadata is submitted through [fdroiddata](https://gitlab.com/fdroid/fdroiddata).

---

## Privacy

- No ads, no analytics SDKs  
- Location is used **only** for optional distance alerts and is not uploaded to a TyphoonEye server  
- Weather requests go to third-party APIs you configure  

Full text: [PRIVACY.md](PRIVACY.md)

---

## Contributing

Issues and pull requests are welcome.

1. Fork + branch from `master` (GitHub edition) or `foss` (F-Droid edition)  
2. Keep changes focused; match existing style  
3. Do **not** commit `local.properties`, keystores, or real API keys  
4. Prefer `./gradlew test` before opening a PR  

---

## License

[Apache License 2.0](LICENSE)

| File | Purpose |
|------|---------|
| [LICENSE](LICENSE) | Full Apache License 2.0 text |
| [NOTICE](NOTICE) | Copyright and third-party attribution notes |

In the app: **Settings → About → Open Source Licenses**.

Map data/providers and weather APIs remain under their own terms.
