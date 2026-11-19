# M328V6 PC 上位机（Web Serial / Web Bluetooth / PWA 桌面应用）

基于现代浏览器原生硬件 API（Web Serial API 与 Web Bluetooth API）开发的 M328V6 数控电子负载全平台上位机。
无需安装任何 Python 依赖、Node.js 或 .NET 运行库，单文件即可运行在 **Windows、Linux、macOS** 上。

---

## 浏览器兼容性要求（重要）

- **支持的浏览器**：**Google Chrome**、**Microsoft Edge**、**Brave**、**Chromium** 等基于 Chromium 内核的现代桌面浏览器。
- **不支持的浏览器**：
  - **Firefox (火狐)**：因 Mozilla 官方安全策略拒绝支持 Web Serial API。
  - **Safari (苹果原生浏览器)**：Apple 官方未实现 Web Serial API。
  - *在 Linux / macOS 下请务必使用 Chrome、Edge 或 Chromium 打开。*

---

## 运行方式

### 方式 1：双击直接打开（最快、单文件免安装）
直接使用 Chrome / Edge 双击打开 [`m328v6_web_host.html`](m328v6_web_host.html) 即可。
点击右上角「连接设备」：
- **USB 串口 (Web Serial)**：选择 CH340 / CP2102 / PL2303 等 USB 转串口设备。
- **低功耗蓝牙 (Web Bluetooth BLE)**：免配对直接无线搜索连接 HC-08 / JDY-08 / JDY-23 等 BLE 蓝牙模块。

### 方式 2：PWA 独立桌面客户端模式（推荐体验）
启动本地轻量服务后，可一键将其安装为系统原生的独立应用窗口（脱离浏览器地址栏与标签页，常驻系统任务栏与启动菜单，完全支持离线秒开）：
- **Windows**：双击运行 [`start_pwa_server.bat`](start_pwa_server.bat)。
- **Linux / macOS**：在终端运行：
  ```bash
  chmod +x start_pwa_server.sh
  ./start_pwa_server.sh
  ```
浏览器唤起后，地址栏右侧会显示 **「安装应用」** 电脑图标，点击即可安装到系统桌面和启动台（macOS Dock / Linux 应用菜单）。

---

## Linux 系统权限与配置说明

Linux 下基于严格的设备权限控制，初次使用 USB 串口时通常需要简单的组权限配置：

### 1. 串口访问权限（dialout / uucp 组）
Linux 串口设备节点（如 `/dev/ttyUSB0`、`/dev/ttyACM0`）默认归属于特定硬件组，普通用户直接访问会提示无法打开或没有权限。
根据您的 Linux 发行版，将当前用户加入对应的串口用户组：

```bash
# Ubuntu / Debian / Linux Mint / Deepin
sudo usermod -aG dialout $USER

# Arch Linux / Manjaro
sudo usermod -aG uucp $USER

# Fedora / CentOS / RHEL
sudo usermod -aG dialout $USER
```
> **注意**：添加组后，请**注销当前桌面会话并重新登录**（或重启），用户组权限才会对浏览器进程生效。

### 2. 免注销 udev 规则配置（可选推荐）
如果希望任何用户均可即插即用读写 USB 串口，可添加一条 udev 规则：
```bash
echo 'KERNEL=="ttyUSB[0-9]*", MODE="0666"' | sudo tee /etc/udev/rules.d/99-m328v6-serial.rules
echo 'KERNEL=="ttyACM[0-9]*", MODE="0666"' | sudo tee -a /etc/udev/rules.d/99-m328v6-serial.rules
sudo udevadm control --reload-rules && sudo udevadm trigger
```

### 3. Ubuntu 22.04+ 下 CH340 自动断开问题（brltty 冲突）
在 Ubuntu 22.04 / 24.04 等较新版本中，系统默认安装了 `brltty`（盲文终端驱动）。当插入 CH340 USB 串口芯片时，该服务会误抢占设备，导致串口在插入 1~2 秒后自动消失或断连。
**解决办法**：如果您不需要盲文设备，直接卸载该冲突包即可：
```bash
sudo apt remove brltty
```

### 4. 蓝牙 BLE 支持
如需使用低功耗蓝牙功能，确保系统 `bluetooth` 服务正在运行：
```bash
sudo systemctl start bluetooth
```

---

## macOS 系统配置与权限说明

macOS 整体开箱即用度较高，需注意以下几点：

### 1. 浏览器选择
macOS 原生 Safari 浏览器不支持 Web Serial API，请下载并使用 **Google Chrome for Mac** 或 **Microsoft Edge for Mac**。

### 2. 串口识别与驱动
- macOS 10.14+ 及 Apple Silicon 架构（M1 / M2 / M3 / M4 系列芯片）内置了主流 USB 串口驱动，插入设备后即可在 Chrome 弹窗列表中看到类似 `USB-Serial Controller` 或 `WCH USB Serial` 设备。
- 如果使用的是较老版本的 CH340 芯片且浏览器弹出列表为空，可前往沁恒官网安装最新的 **CH34x macOS 驱动**（支持 Apple Silicon）。

### 3. 蓝牙权限许可（macOS 11 Big Sur 及更高版本）
如果使用 Web Bluetooth BLE 功能，macOS 首次调用系统蓝牙时会弹出权限授权弹窗。
若不慎点击了拒绝，可在系统中重新开启：
`系统设置 (System Settings) -> 隐私与安全性 (Privacy & Security) -> 蓝牙 (Bluetooth)` -> 勾选允许 **Google Chrome** 或 **Microsoft Edge**。

---

## 核心文件清单
- `m328v6_web_host.html`：上位机主界面与通信协议核心（仅需此单个文件即可完整使用）
- `start_pwa_server.bat`：Windows PWA 本地应用模式启动器
- `start_pwa_server.sh`：Linux / macOS PWA 本地应用模式启动脚本
- `manifest.webmanifest`：PWA 独立桌面客户端安装描述文件
- `sw.js`：Service Worker 离线缓存引擎
- `icon-192.png` / `icon-512.png`：现代科技极简 V6 高分图标（方案 B）
