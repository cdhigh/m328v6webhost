# M328V6 上位机套件（PC 桌面 / Web / Android 移动端）

本目录包含 M328V6 数控电子负载的全平台上位机软件套件，按平台清晰划分为两个子工程：

## 目录结构

```text
tools/m328v6_android_host/
├── pc/                         # PC 桌面端 / Web 网页版 / PWA 独立应用（支持 Windows / Linux / macOS）
│   ├── m328v6_web_host.html    # 主界面与通信核心（Web Serial / Web BLE）
│   ├── start_pwa_server.bat    # Windows PWA 本地应用一键启动器
│   ├── start_pwa_server.sh     # Linux / macOS PWA 本地应用启动脚本
│   ├── manifest.webmanifest    # 桌面独立应用配置
│   ├── sw.js                   # Service Worker 离线缓存
│   ├── icon-192.png            # 方案 B 极简科技高分图标
│   ├── icon-512.png
│   └── README.md               # PC 使用与 Linux/macOS 权限配置指南
│
├── android/                    # Android 手机 / 平板 原生应用工程
│   ├── app/                    # Android 模块源码（Kotlin + 原生蓝牙/OTG 桥接）
│   ├── gradlew.bat             # Gradle 自动化构建脚本
│   ├── build.gradle.kts        # 自动同步 ../pc/m328v6_web_host.html 到 assets
│   ├── ic_launcher_512.png     # Android 512x512 高分图标
│   └── README.md               # Android 编译与使用指南
│
└── design/                     # 图标设计与对比资源
    ├── icon_redesign_comparison.png
    ├── concept_1_hardware_abstract.png
    ├── concept_2_tech_brand_v6.png
    └── make_icon_concepts.py
```

## 快速入口
- **PC 用户**：直接进入 `pc` 目录，双击打开 `m328v6_web_host.html` 或运行 `pc/start_pwa_server.bat`。
- **Android 用户 / 开发者**：直接使用 Android Studio 打开 `android/`目录即可编译生成 APK。
