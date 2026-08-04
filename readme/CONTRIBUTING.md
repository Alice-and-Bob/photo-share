# 贡献指南

欢迎 Issue 与 Pull Request！

## 提交 Issue

- 先搜索是否已有相同或相近的 Issue；
- 描述环境（设备型号 / Android 版本 / App 版本）、复现步骤与预期行为；
- 构建相关问题请附关键日志与 `gradle.properties` 相关配置。

## 开发流程

1. **Fork** 本仓库并克隆到本地；
2. 基于 `main`（或你的主分支）创建特性分支：
   ```bash
   git checkout -b feature/你的功能     # 新功能
   git checkout -b fix/你修的bug        # 修复
   ```
3. 提交粒度清晰，一个提交只做一件事；
4. 本地通过 `./gradlew test` 与相关插桩测试；
5. 提交前运行 `./gradlew lint` 确保无明显告警；
6. 推送到你的 Fork 并发起 **Pull Request**，描述改动动机与验证方式。

## 代码规范

- 遵循 Kotlin 官方代码风格（`kotlin.code.style=official`）；
- 注释中文 / 英文均可，同一文件内保持一致；
- 新增依赖请加入 `gradle/libs.versions.toml` 版本目录，勿硬编码版本号；
- 涉及网络 / 存储的改动请注意权限（`MANAGE_EXTERNAL_STORAGE`）与后台保活（前台服务 + 锁）的兼容性。

## 提交信息约定（推荐）

```
<type>: <简要描述>

type: feat / fix / docs / refactor / test / chore
示例：
feat: 支持 EXIF 缩略图方向自动校正
fix:  修复热点 IP 变化后 mDNS 未重新广播
docs: 补充 README 安装与使用示例
```

## 行为准则

保持友善、就事论事；对他人的贡献给予尊重与建设性反馈。
