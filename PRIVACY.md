# Privacy Policy — TyphoonEye（台风眼）

**Last updated:** 2026-08-11  
**Application ID:** `seamain.org.typhoonEye`  
**Source:** https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid

This document describes how the TyphoonEye Android application handles data. The app is open source under the Apache License 2.0.

## Summary

- No advertisements  
- No third-party analytics or crash-reporting SDKs bundled for tracking  
- No TyphoonEye backend that collects personal profiles  
- Location is optional and used only for on-device distance alerts  
- Weather content may be requested from third-party APIs when keys are configured  

## Data the app may use

### 1. Location (optional)

- **Types:** approximate and/or precise location (Android permissions)  
- **When:** only if you enable location-related alert features and grant permission  
- **Purpose:** estimate distance between you and active storms; show place labels when reverse-geocoding is available on device  
- **Storage:** last known coordinates / label may be cached **on device** (DataStore) to support background checks  
- **Sharing:** not uploaded to a TyphoonEye-operated server. OS or map/geocoder components may process location according to the device vendor’s policies  

You can deny or revoke location permission in system settings; alert features that need location will not work fully.

### 2. Network weather data

When API keys are present (built into a private build or supplied at compile time), the app may contact:

- Juhe typhoon APIs (if `JUHE_KEY` is set)  
- QWeather tropical cyclone / warning APIs (if QWeather credentials are set)  
- Map tile providers (e.g. Amap, OpenStreetMap/Carto-style sources) depending on basemap settings  

Those providers process requests under **their own** privacy policies and terms. Public F-Droid / demo builds may ship **without** keys and rely on demo or cached data.

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

API keys embedded in an APK can be extracted. Prefer:

- Demo mode for public testing, or  
- Your own keys in private/local builds, or  
- A self-hosted proxy (advanced)  

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
- **气象数据：** 若构建时配置了密钥，请求会发往聚合、和风等第三方，以及所选地图瓦片服务，适用对方隐私条款。无密钥时以演示/缓存为主。  
- **更新：** GitHub 渠道可能检查 GitHub Releases；F-Droid 渠道关闭该能力。  
- **设置与通知：** 保存在本机；通知内容由本机根据已获取的风暴数据生成。  

完整说明以本文英文条款为准。问题请通过 GitHub Issues 联系。
