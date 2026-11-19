# M328V6 Android 原生上位机工程

基于 `WebView` + [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android) + Android 原生经典蓝牙 SPP（RFCOMM）+ 低功耗蓝牙（BLE GATT）封装的 Android 移动端上位机。

## 核心特性
- **三通道连接**：USB OTG 串口（免 root）、经典蓝牙 SPP（HC-06/JDY-31 自动重连）、低功耗蓝牙 BLE（HC-08/JDY-08 动态扫描免配对）。
- **现代自适应图标**：采用业界主流审美的科技品牌 V6 极简矢量图标（方案 B），完美契合 Material You 圆形/方圆蒙版规范。
- **自动同步 HTML 前端**：Gradle 在每次构建（`preBuild`）前自动执行 `syncWebHostHtml` 任务，将 `../pc/m328v6_web_host.html` 同步到 `app/src/main/assets/`，确保 PC 与 Android 前端源码始终 100% 保持单一事实来源。
- **后台保活与前台服务**：连接期间自动启动 `SerialKeepAliveService` 前台服务，锁屏或切换应用持续采样并记录曲线，防止后台断流。
- **文件导出与回放**：曲线数据可一键导出为 `.xlsx` / `.csv` / `.png` 保存到系统 `Download/M328V6/` 目录，支持导入历史 CSV 回放。

## 编译方式
使用 **Android Studio** 直接打开本目录（`tools/m328v6_android_host/android`），或在命令行运行：
```bash
./gradlew.bat assembleDebug
```
生成的 APK 位于：
`app/build/outputs/apk/debug/app-debug.apk`
