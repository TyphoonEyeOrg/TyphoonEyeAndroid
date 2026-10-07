# 台风眼（TyphoonEye）

[![Build Android APK](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/actions/workflows/build-apk.yml/badge.svg)](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/actions/workflows/build-apk.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![Android SDK](https://img.shields.io/badge/API-29%2B-3DDC84.svg?style=flat&logo=android)](https://developer.android.com/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![F-Droid](https://img.shields.io/badge/F--Droid-metadata-1976D2.svg?style=flat)](metadata/seamain.org.typhoonEye.yml)

[English](README.md) | 中文

**台风眼（TyphoonEye）** 是一款开源 Android 应用，面向**西北太平洋台风**实时路径、多象限风圈、预报轨迹与可选距离预警。

基于 **Jetpack Compose** 与 **Material Design 3**。

> **免责声明：** 本应用为第三方客户端，**不是**任何气象机构的官方产品。紧急情况下请以当地官方预警与政府指引为准。

---

## 功能

- **MapLibre** 交互地图：历史路径、预报路径、七 / 十 / 十二级分象限风圈  
- 台风详情：气压、风速、移速移向、观测时间线  
- 可选**距离感知紧急预警**（需定位权限）  
- 实时状态通知 + 后台刷新（`WorkManager`）  
- Material 3、动态取色、深浅色主题  
- 语言：简体中文、繁體中文、粵語、English  
- **自带密钥：** 在「设置 → 自定义数据源密钥」中填入自己的和风天气（API Key + API Host）或聚合数据 API 密钥即可获取实时数据；密钥只保存在本机（不上传、不写日志、不进入备份与设备迁移）  
- 完全没有密钥时会说明「此版本未配置实时数据源」，提供跳转到密钥设置的按钮，以及明确标注的**演示模式**（虚构示例台风，不含实时数据，release 构建不发通知）  
- 双渠道：
  - `github`：支持从 GitHub Releases 应用内更新  
  - `fdroid`：仅通过 F-Droid 更新（无侧载安装权限）

---

## 技术栈

| 领域 | 方案 |
|------|------|
| 界面 | Jetpack Compose、Material 3、Navigation |
| 地图 | [MapLibre Native Android](https://github.com/maplibre/maplibre-native) |
| 注入 | Hilt |
| 网络 | Retrofit、OkHttp、可选和风 JWT（Ed25519） |
| 存储 | Room、DataStore |
| 后台 | WorkManager |
| CI | GitHub Actions |

架构：**MVVM** + 分层（UI → ViewModel → Repository → 远端/本地）。

---

## 环境要求

- Android Studio Ladybug（2024.2+）或更新  
- JDK **21**  
- Android SDK **36**（minSdk **29**）

---

## 编译

```bash
git clone https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid.git
cd TyphoonEyeAndroid
```

### 可选 API Key

```bash
cp local.properties.example local.properties
```

| 配置项 | 用途 |
|--------|------|
| `JUHE_KEY` | 聚合台风列表 / 详情（可选） |
| `QWEATHER_API_KEY` 或 JWT 字段 | 和风台风与预警（可选） |
| `AMAP_KEY` | 中国大陆高德栅格底图（可选） |

构建时密钥是可选的：用户也可以在应用内「设置 → 自定义数据源密钥」填写自己的密钥（和风 API Key + API Host，
或聚合密钥）。应用内填写的密钥优先于构建时密钥；密钥按请求读取，无需重启应用。

完全没有密钥时也可安装运行，但**无法显示实时台风数据**：首页会提示未配置数据源，提供「填写 API 密钥」按钮，
以及可选的、明确标注「示例数据，非真实台风」的**演示模式**。

> **F-Droid 版：** F-Droid 编译 `fdroid` 渠道时不内置任何 API Key。如需实时数据，请到
> [和风天气](https://console.qweather.com/)（API Key + 账号专属 API Host）或[聚合数据](https://www.juhe.cn/)
> 申请自己的 API 密钥，并在设置中填写。密钥只保存在本机（独立 DataStore 文件，不进入云备份和设备迁移，日志中脱敏），
> 只会发送给对应的数据服务商。

详见 [local.properties.example](local.properties.example) 与 [PRIVACY.md](PRIVACY.md)。

### 常用命令

```bash
# GitHub 渠道 Debug（默认）
./gradlew assembleGithubDebug

# GitHub 渠道 Release（配置签名后）
./gradlew assembleGithubRelease

# F-Droid 渠道（关闭 GitHub 应用内更新）
./gradlew assembleFdroidRelease
```

### 版本号

以根目录 [`version.properties`](version.properties) 为准：

```properties
VERSION_NAME=1.2.0
VERSION_CODE=15
```

发版请同步改此文件，并打 tag：`v1.2.0`。CI 可用环境变量覆盖。

---

## 发布与 CI

工作流： [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)

- 推送 / PR → 构建产物  
- 推送 `v*` 标签 → GitHub Release + `github` 渠道 APK  

可选仓库 Secrets：API Key、Release 签名密钥库。

---

## F-Droid 投稿说明

**应用源码继续放在 GitHub，不必整体迁到 GitLab。**

向 F-Droid 投稿时，只需在 GitLab 的 **[fdroiddata](https://gitlab.com/fdroid/fdroiddata)** 提交 metadata 的 Merge Request。

仓库内已准备：

| 路径 | 说明 |
|------|------|
| `metadata/seamain.org.typhoonEye.yml` | 可复制到 fdroiddata 的构建描述 |
| `metadata/en-US`、`metadata/zh-CN` | 商店文案 |
| `version.properties` | 版本检测 |
| productFlavor `fdroid` | 关闭 GitHub 侧载更新 |
| `AntiFeatures: NonFreeNet` | 第三方气象接口 / 可选高德瓦片 |

### 投稿步骤

1. 提交本仓库改动并推送到 GitHub  
2. 更新 `version.properties`，打 tag 并推送，例如：
   ```bash
   git tag v1.2.0
   git push origin foss   # 或你的主分支
   git push origin v1.2.0
   ```
3. 本地验证无密钥可编过：
   ```bash
   ./gradlew assembleFdroidRelease
   ```
4. 在 `metadata/*/images/phoneScreenshots/` 放入至少 2 张截图；建议提供 512×512 `icon.png`  
5. Fork fdroiddata → 复制 yml → 将 `Builds.commit` 设为该 tag → 开 MR  

---

## 隐私

- 无广告、无统计 SDK  
- 定位**仅**用于可选距离预警，不会上传到台风眼自有服务器  
- 气象请求发往你配置的第三方 API  

全文见 [PRIVACY.md](PRIVACY.md)。

---

## 参与贡献

欢迎 Issue 与 Pull Request。

1. 从 `foss` 或 `master` 拉分支  
2. 改动尽量聚焦，风格与现有代码一致  
3. **不要**提交 `local.properties`、密钥库或真实 API Key  
4. 建议 PR 前执行 `./gradlew test`

---

## 许可证

[Apache License 2.0](LICENSE)

地图与气象数据服务条款以各提供方为准。
