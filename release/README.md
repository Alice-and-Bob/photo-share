# release/

本目录用于存放**本地构建产物**，其中的 `*.apk` / `*.aab` **不纳入版本控制**。

APK 约 24 MB 且每次构建内容都会变，直接入库会让仓库体积随构建次数线性膨胀，且二进制无法做有意义的 diff。分发请走 GitHub Release 附件、制品库，或按需启用 Git LFS。

## 自行构建

```bash
# 项目根目录
export ANDROID_HOME=/path/to/Android/Sdk   # 或在 local.properties 中写 sdk.dir
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk release/photo_share-release-v1.apk
```

Windows / 受限环境下 `gradlew.bat` 可能因 classpath 解析失败，可直接用 wrapper JAR 启动：

```bash
export JAVA_HOME="/path/to/jdk-21"
"$JAVA_HOME/bin/java" -jar gradle/wrapper/gradle-wrapper.jar --no-daemon assembleRelease
```

## 安装

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

- 安装后**无需任何授权步骤**：照片经 MediaStore 写入 `DCIM/PhotoShare`，App 不申请任何存储权限。
- MIUI / HyperOS 需先解锁屏幕并在开发者选项中开启「**USB 安装**」，否则会报 `INSTALL_FAILED_USER_RESTRICTED`。

## 签名说明

`app/build.gradle.kts` 中的 release 签名复用本机 `~/.android/debug.keystore`，仅供直接安装与内部测试。上架应用商店前请替换为专用发布密钥。
