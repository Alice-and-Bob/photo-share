# Photo Share

把 Android 设备变成一台无线照片服务器：相机通过 FTP 把照片推送到设备，同一局域网下的任意设备用浏览器即可浏览、下载原图。

- FTP 服务（Apache FtpServer）：相机自动上传照片到 `DCIM/PhotoShare`
- HTTP 服务（NanoHTTPD）：照片墙网页、缩略图、原图断点续传
- mDNS 零配置：浏览器用 `http://photoshare.local` 直接访问
- 二维码：App 内展示访问二维码，扫码即开

适合相机 + 手机热点组成的临时无线影棚、活动现场即时选片、无有线连接的快速传图。

---

## 功能特性

- **相机自动上传**：配置好 FTP 后，拍摄即推送，照片自动落盘到 `DCIM/PhotoShare`（系统相册可见）。
- **照片墙浏览**：响应式网格、分页加载、大图查看（含 EXIF）、一键下载原图。
- **零配置访问**：HTTP 通过 `photoshare.local`（mDNS）访问；IP 变化时自动重新广播。
- **断点续传**：原图下载支持 HTTP `Range`。
- **稳定常驻**：前台服务 + 唤醒锁 + Wi-Fi 锁，避免后台被回收。
- **端口自适应**：HTTP 优先 80、失败回退 8080/18080；FTP 优先 21、失败回退 2121。

---

## 环境要求

- **设备**：Android 10（API 29）及以上
- **构建**：JDK 21、Android SDK（`compileSdk` 36），项目自带 `gradlew`
- **权限**：安装后需授予「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE`）以写入 `DCIM/PhotoShare`
- **网络**：设备开热点，相机与浏览端连接同一热点

---

## 安装

**方式一：安装预构建 APK**

```
release/photo_share-release-v1.apk
```

拷贝到设备 → 允许「未知来源」安装 → 首次打开授予「所有文件访问权限」。该包使用本机 debug keystore 签名，仅供直接安装 / 内部测试。

**方式二：从源码构建**

```bash
cd sony_ftp
export ANDROID_HOME=/path/to/Android/Sdk   # 或编辑 local.properties 写 sdk.dir
./gradlew assembleRelease                  # 输出 app/build/outputs/apk/release/
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 配置

停止态可在 App 内「服务器设置」修改，配置经 `SharedPreferences` 持久化：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| FTP 用户名 | `camera` | 相机登录账号 |
| FTP 密码 | `camera123` | 相机登录密码 |
| FTP 端口 | `2121` | 优先 21，失败回退 2121 |
| HTTP 端口 | `8080` | 优先 80，失败回退 8080 / 18080 |
| 被动端口段 | `50000-50100` | FTP PASV 数据端口范围 |
| mDNS 主机名 | `photoshare.local` | 仅用于 HTTP 访问 |

> FTP 不使用 mDNS 域名发现，相机须填 App 内显示的**直连 IP** 上传。

---

## 使用示例

### 1. 相机 FTP 上传（核心）

1. 打开 App → 启动服务器（授予「所有文件访问权限」）。
2. 开启手机热点，记下 App 内显示的 FTP 地址（直连 IP，如 `192.168.43.1:2121`）。
3. 相机连接该热点，FTP 设置：
   - 服务器：App 内显示的 IP（**不是** `photoshare.local`）
   - 端口：`2121`
   - 用户名：`camera` / 密码：`camera123`
   - 模式：**PASV 被动模式**
4. 拍摄后照片自动上传到 `DCIM/PhotoShare`，并即时出现在照片墙。

### 2. 浏览器浏览照片墙

同一热点下的设备打开浏览器：

- 推荐：`http://photoshare.local`
- 或 App 内二维码 / 显示的 IP：`http://<设备IP>:8080`

### 3. HTTP 接口

照片墙背后的接口（已开启 CORS）：

| 接口 | 说明 |
| --- | --- |
| `GET /api/photos?page=0&size=100` | 照片列表（分页 JSON） |
| `GET /api/status` | 服务状态（照片数、端口、IP） |
| `GET /thumb/<filename>` | 缩略图（JPEG） |
| `GET /download/<filename>` | 原图下载（支持 `Range`） |

```bash
curl "http://photoshare.local:8080/api/status"
curl -O "http://photoshare.local:8080/download/DSC00001.jpg"
```

---

## 项目结构

```
sony_ftp/
├── app/src/main/            # 源码：ftp/ http/ repository/ database/ mdns/ ui/ util/ 等
│   └── assets/web/          # 照片墙网页（index.html / app.js / style.css）
├── gradle/                  # Gradle 包装器 + 版本目录
├── build.gradle.kts         # 顶层构建
├── settings.gradle.kts      # 仓库镜像、模块声明
├── gradle.properties        # Gradle / Kotlin 守护进程参数
├── release/                 # 预构建 APK
└── readme/                  # 补充文档（架构 / 开发 / 贡献 / FAQ）
```

关键依赖：Jetpack Compose、Room（KSP）、Apache FtpServer、NanoHTTPD、ZXing、JmDNS、androidx ExifInterface。

---

## 贡献

欢迎 Issue 与 Pull Request，详见 [readme/CONTRIBUTING.md](readme/CONTRIBUTING.md)。架构与数据流见 [readme/ARCHITECTURE.md](readme/ARCHITECTURE.md)，开发构建与测试见 [readme/DEVELOPMENT.md](readme/DEVELOPMENT.md)，常见问题见 [readme/FAQ.md](readme/FAQ.md)。

---

## 许可证

[MIT](LICENSE) © Photo Share contributors
