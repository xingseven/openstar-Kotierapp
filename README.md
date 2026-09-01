# kotier

`kotier` 是一个基于 [EasyTier](https://github.com/EasyTier/EasyTier) 的 Android 异地组网客户端，目标是把“配置网络、启动 VPN、查看节点、快速联机”收敛到手机端完成。

当前仓库以 Android 客户端为主，界面使用 Kotlin + Jetpack Compose，实现上通过 JNI 对接 EasyTier 原生能力，并在仓库内保留了面向 Android 的后端抽象层，方便持续演进。

## APP截图

<img src="images/phone.png" width="360" alt="手机端截图"/>

## 项目现状

- 当前主应用是 Android 版 `kotier`
- 应用包名：`com.easytier.app.split`
- 当前版本：`4.2.46 (4064)`
- 当前默认只构建 `arm64-v8a`
- GitHub Release 通过标签 `v*` 触发自动打包

## 当前能力

- 普通网络页：支持多套配置的创建、保存、导入、启动和停止
- 一键联机页：支持房主建网、房客输入编码加入网络
- 服务器页：支持查看公共节点状态、收藏入口服务器
- 设置页：支持主题、日志级别、版本信息和项目入口
- 日志页：支持查看内存日志，便于排查 JNI / VPN / 网络实例状态
- Android VPN 集成：通过 `VpnService` 建立 TUN，并与 EasyTier 实例联动
- 节点监控：启动后可轮询节点信息和本机虚拟 IP

## 导入兼容

当前 Android 网络配置页已经支持双兼容导入：

- `qt-easy-tier-master` 导出的 `JSON`
- `qt-easy-tier-master` / `EasyTier-main` 导出的 `TOML`

说明：

- JSON 兼容主要面向 QtEasyTier 的配置导出格式
- TOML 兼容主要面向 EasyTier 官方配置格式
- TOML 导入优先覆盖网络名、密钥、DHCP/IPv4、监听地址、入口服务器、子网代理、路由、出口节点和主要 flags

## 技术栈

- 语言：Kotlin
- UI：Jetpack Compose + Material 3
- 异步：Kotlin Coroutines
- 网络：OkHttp
- 原生对接：JNI
- 组网引擎：EasyTier Rust 原生库
- VPN：Android `VpnService`
- 构建：Gradle Kotlin DSL

## 仓库结构

```text
.
├── android/                 # Android 应用工程
│   ├── app/
│   │   └── src/main/java/com/easytier/
│   │       ├── backend/     # Android 侧后端调用适配
│   │       ├── service/     # EasyTierService / VPN / 设置 / 日志
│   │       ├── ui/
│   │       │   ├── components/
│   │       │   ├── pages/
│   │       │   └── theme/
│   │       ├── EasyTierApplication.kt
│   │       └── MainActivity.kt
│   ├── build-and-install.ps1
│   └── FRONTEND_DEVELOPMENT_GUIDE.md
├── backend/                 # 共享后端抽象与数据模型
│   ├── adapters/
│   ├── core/
│   ├── protocol/
│   └── src/
├── .github/workflows/
│   └── android-release.yml  # 标签发版工作流
├── install_apk.py           # 本地 adb 安装脚本
├── install_apk.bat          # Windows 快速安装入口
└── 版本记录.md
```

## 架构说明

- Android UI 不直接持有 JNI 细节，而是优先通过 `EasyTierService` 和 `AndroidAdapter` 访问底层能力
- `backend/` 用来沉淀协议模型和跨页面复用的数据结构，减少页面逻辑对底层实现的直接耦合
- VPN 生命周期由 Android 侧 `EasyTierVpnService` 承担，网络实例生命周期由 `EasyTierService` 协调

## 构建要求

- JDK 17
- Android SDK 35
- `minSdk = 26`
- 推荐在 Windows 或 Android Studio 环境下构建

## 本地构建

使用 Android Studio 打开 `android/` 目录即可同步工程，或者直接使用命令行：

```powershell
cd android
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

release 产物默认位于：

```text
android/app/build/outputs/apk/release/kotier-v<version>-<versionCode>-release.apk
```

## 本地安装到手机

仓库根目录提供了一个轻量 adb 安装脚本：

```powershell
python .\install_apk.py .\android\app\build\outputs\apk\release\kotier-v4.2.44-4062-release.apk
```

脚本会优先自动寻找本机 Android SDK 下的 `adb.exe`。

## 原生库依赖

构建前需要准备 EasyTier 对应的原生库，并放到 Android 工程可引用的位置。当前项目依赖 JNI 对接 EasyTier 原生能力，常见包括：

- `easytier_ffi.so`
- `easytier_android_jni.so`

如果缺失这些原生库，应用可以通过 Gradle 编译 Kotlin 层，但无法完整运行组网功能。

## 相关参考

- 仓库地址：`https://github.com/sevencnup/wotty-kotierapp`
- 版本记录：[版本记录.md](/F:/1python/xiangmu/wotty-kotierapp/版本记录.md)
- Android 前端说明：[FRONTEND_DEVELOPMENT_GUIDE.md](/F:/1python/xiangmu/wotty-kotierapp/android/FRONTEND_DEVELOPMENT_GUIDE.md)
