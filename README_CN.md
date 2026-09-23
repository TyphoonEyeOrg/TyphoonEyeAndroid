# 台风眼（TyphoonEye）

[![Build Android APK](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/actions/workflows/build-apk.yml/badge.svg)](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/actions/workflows/build-apk.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org/)
[![Android SDK](https://img.shields.io/badge/API-29%2B-3DDC84.svg?style=flat&logo=android)](https://developer.android.com/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[English](README.md) | 中文

**台风眼（TyphoonEye）** 是一款开源 Android 应用，面向**西北太平洋台风**实时路径、多象限风圈、预报轨迹与可选距离预警。

基于 **Jetpack Compose** 与 **Material Design 3**。

当前 `master` 分支是 **GitHub 版**：使用 Play 定位服务，并从 GitHub Releases 做应用内更新。

> **免责声明：** 本应用为第三方客户端，**不是**任何气象机构的官方产品。紧急情况下请以当地官方预警与政府指引为准。

---

## 功能

- **MapLibre** 交互地图：历史路径、预报路径、七 / 十 / 十二级分象限风圈  
- 台风详情：气压、风速、移速移向、观测时间线  
- 可选**距离感知紧急预警**（需定位权限）  
- 实时状态通知 + 后台刷新（`WorkManager`）  
- Material 3、动态取色、深浅色主题  
- 语言：简体中文、繁體中文、粵語、English  
- 未配置密钥时使用**演示数据 / 离线缓存**  
- 从 GitHub Releases 应用内更新  

---

## 技术栈

| 领域 | 方案 |
|------|------|
| 界面 | Jetpack Compose、Material 3、Navigation |
| 地图 | [MapLibre Native Android](https://github.com/maplibre/maplibre-native) |
| 定位 | Google Play 定位服务 |
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

不填密钥也可安装运行（演示 / 缓存模式）。  
详见 [local.properties.example](local.properties.example) 与 [PRIVACY.md](PRIVACY.md)。

### 常用命令

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

### 版本号

以根目录 [`version.properties`](version.properties) 为准：

```properties
VERSION_NAME=1.2.0
VERSION_CODE=15
```

CI 可用环境变量覆盖。

---

## 发布与 CI

工作流： [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)

- 向 `master` 推送 / PR → 构建产物  
- 正式 `v*` GitHub Release 从 [`foss`](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/tree/foss) 分支打 tag，以便与 F-Droid 对齐  

不要从 `master` 推送 `v*` 标签，除非你打算覆盖该版本的发行包。

可选仓库 Secrets：API Key、Release 签名密钥库。

---

## F-Droid

无 Play 服务、关闭侧载更新的打包在 [`foss`](https://github.com/TyphoonEyeOrg/TyphoonEyeAndroid/tree/foss) 分支。应用源码仍在 GitHub，只通过 [fdroiddata](https://gitlab.com/fdroid/fdroiddata) 提交 metadata。

---

## 隐私

- 无广告、无统计 SDK  
- 定位**仅**用于可选距离预警，不会上传到台风眼自有服务器  
- 气象请求发往你配置的第三方 API  

全文见 [PRIVACY.md](PRIVACY.md)。

---

## 参与贡献

欢迎 Issue 与 Pull Request。

1. 从 `master`（GitHub 版）或 `foss`（F-Droid 版）拉分支  
2. 改动尽量聚焦，风格与现有代码一致  
3. **不要**提交 `local.properties`、密钥库或真实 API Key  
4. 建议 PR 前执行 `./gradlew test`

---

## 许可证

[Apache License 2.0](LICENSE)

| 文件 | 说明 |
|------|------|
| [LICENSE](LICENSE) | Apache License 2.0 全文 |
| [NOTICE](NOTICE) | 版权与第三方组件致谢 |

应用内路径：**设置 → 关于 → 开源许可与致谢**。

地图与气象数据服务条款以各提供方为准。
