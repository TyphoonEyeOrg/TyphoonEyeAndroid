# Privacy Policy — TyphoonEye（台风眼）

**Last updated:** 2026-10-08  
**Application ID:** `seamain.org.typhoonEye`  
**Source:** https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid

This document describes how the TyphoonEye Android application handles data. The app is open source under the Apache License 2.0.

## Summary

- No advertisements  
- No third-party analytics or crash-reporting SDKs bundled for tracking  
- No accounts and no TyphoonEye backend that collects personal profiles  
- Location is optional and used only for on-device distance alerts  
- **F-Droid build:** typhoon data requests go through Cloudflare and TyphoonEye's own relay server, which holds the weather API keys. The relay uses your IP address only for rate limiting and does not store it. Your location is never sent to the relay  
- **GitHub build:** typhoon data is requested directly from third-party weather APIs with keys built into that build  

## Data the app may use

### 1. Location (optional)

- **Types:** approximate and/or precise location (Android permissions)  
- **When:** only if you enable location-related alert features and grant permission  
- **Purpose:** estimate distance between you and active storms; show place labels when reverse-geocoding is available on device  
- **Storage:** last known coordinates / label may be cached **on device** (DataStore) to support background checks  
- **Sharing:** not uploaded to a TyphoonEye-operated server. OS or map/geocoder components may process location according to the device vendor’s policies  

You can deny or revoke location permission in system settings; alert features that need location will not work fully.

### 2. Network weather data

#### F-Droid build (`fdroid` flavor): through the TyphoonEye relay

The F-Droid build contains **no** weather API keys. All typhoon data requests go to `https://te-relay.seamain.org`, a relay server run by the TyphoonEye developer on **Cloudflare** (source code: [`relay/`](relay/)). The relay holds the API keys and fetches the data from the third-party providers (QWeather / Juhe) on the app's behalf.

- **What the app sends:** only the typhoon list / detail request itself (for example a storm id or the current year). No API key, no location, no device or account identifier.  
- **IP address:** like any web request, the connection reveals your IP address to Cloudflare and to the relay. The relay uses it only for rate limiting (as a salted hash, kept in memory for the current rate-limit window) and does **not** log or store it. The relay keeps no request logs. Cloudflare, as the hosting provider, processes connections under [its own privacy policy](https://www.cloudflare.com/privacypolicy/).  
- **Providers:** the relay forwards no data from your device to QWeather or Juhe — no IP address, headers or location; the providers only see requests from the relay. Responses are cached on the relay (lists 10 minutes, storm details 30 minutes) and shared by all users.  
- **Official warnings without your location:** the relay regularly fetches typhoon-related official warnings for a fixed list of coastal cities and serves the same list to every user. The app downloads the whole list and picks warnings near you **on your device**. Your location is never sent to the relay or to any weather provider.  

#### GitHub build (`github` flavor): direct requests

GitHub release builds include API keys at build time and contact the providers directly:

- Juhe typhoon APIs  
- QWeather tropical cyclone APIs  
- QWeather weather-warning API: with location permission, official warnings are looked up at your approximate position (rounded to 0.01°, about 1 km) and at the centres of active storms; without it, at the centres of active storms and a fixed list of coastal cities  

A build made without keys (for example a local build without `local.properties` keys) makes no weather-data requests.

#### Both builds

- Map tiles come from map tile providers (e.g. Amap, OpenStreetMap/Carto-style sources) depending on basemap settings.  
- Third-party providers process requests under **their own** privacy policies and terms.  
- If live data cannot be loaded and nothing is cached, the app offers an optional, clearly labeled demo mode with bundled fictional storms.  

### 3. Update checks (GitHub flavor only)

Builds with the `github` product flavor may query the **GitHub Releases API** for this repository to offer in-app updates, and may download an APK you choose to install.  

**F-Droid flavor** disables this path; update through the F-Droid client.

### 4. Notifications

With permission, the app may show ongoing “live status” and emergency-style notifications. Notification content is generated on device from storm data you already fetched.

### 5. App preferences

Theme, language, basemap choice, alert toggles, and similar settings are stored **locally** on the device.

## Data we do not collect

TyphoonEye does not operate an account system and does not intentionally collect:

- Name, email, or phone number  
- Advertising IDs for ads  
- Analytics event streams to a TyphoonEye server  

## Children

The app is a general-purpose weather utility, not directed at children. Do not enable location features on a child’s device without appropriate guardian consent under local law.

## Security

API keys embedded in an APK can be extracted. The F-Droid build therefore contains none: the keys exist only as secrets of the TyphoonEye relay, which accepts only the few requests the app needs, validates their parameters and rate-limits clients. The GitHub build embeds keys at build time; if you build the app yourself, use your own keys in private/local builds.

Never commit real keys or keystores to git.

## Your choices

- Revoke location / notification permissions in Android settings  
- Clear app storage to remove local cache and preferences  
- Uninstall the app  

## Changes

Privacy practices may change as features evolve. Material changes will be reflected in this file and the application version history.

## Contact

Open an issue on GitHub:  
https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/issues

---

# 隐私政策（中文摘要）

**台风眼**是开源应用，不含广告与统计 SDK，也没有用于收集个人档案的台风眼自有账号服务器。

- **定位（可选）：** 仅在开启相关预警并授权后，于设备本地估算与台风距离；可缓存最近位置标签。不会上传到台风眼自有服务器。  
- **F-Droid 版的气象数据：** 应用内不含任何 API 密钥。台风数据请求经 Cloudflare 发往台风眼自己的中转服务器（`te-relay.seamain.org`，源码见 `relay/`），由中转服务器持有密钥，向第三方服务商（和风 / 聚合）获取数据。中转服务器会用到你的 IP 地址做限流，但不保存，也不记录请求日志；Cloudflare 作为托管方按其自身隐私政策处理连接。你的位置不会发给中转服务器：中转服务器定时获取一组固定沿海城市的台风相关官方预警，所有用户拿到同一份列表，附近的预警在本机筛选。  
- **GitHub 版的气象数据：** 构建时内置密钥，直接请求聚合、和风等第三方；授权定位后，官方预警按你的大致位置（保留两位小数，约 1 公里）向和风查询。  
- **两个版本：** 地图瓦片直接请求所选瓦片服务（如高德）。第三方服务适用对方隐私条款。无法获取实时数据且本机没有缓存时，可选择进入明确标注的演示模式（内置虚构台风）。  
- **更新：** GitHub 渠道可能检查 GitHub Releases；F-Droid 渠道关闭该能力。  
- **设置与通知：** 保存在本机；通知内容由本机根据已获取的风暴数据生成。  

完整说明以本文英文条款为准。问题请通过 GitHub Issues 联系。
