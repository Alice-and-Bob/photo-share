# 开发与构建

- 语言：Kotlin 2.2（官方代码风格 `kotlin.code.style=official`）
- UI：Jetpack Compose（Material 3）
- 构建：Gradle Kotlin DSL + Version Catalog（`gradle/libs.versions.toml`）

## 常用命令

```bash
./gradlew assembleDebug        # 调试构建
./gradlew assembleRelease      # 发布构建（本机 debug keystore 签名）
./gradlew lint                 # 代码检查（release 构建已关闭 abortOnError）
./gradlew clean                # 清理
```

> 内存受限环境：`gradle.properties` 已限制守护进程内存（`-Xmx640m` 等）并关闭配置缓存。更宽裕的机器可酌情调大；遇 OOM 请保持现有保守参数。

## 测试

项目包含单元测试（JVM）与插桩测试（Android）两层：

```bash
./gradlew test                 # 单元测试：EXIF、JSON、Range、文件稳定检测
./gradlew connectedAndroidTest # 插桩测试：Room DAO、FTP 上传、HTTP、FileObserver、缩略图
```
