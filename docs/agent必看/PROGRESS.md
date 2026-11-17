# PROGRESS · 进度日志

> 新记录追加在**最上方**。格式见 `01_开发规范.md §12`。

## 待决策

- **首次 GitHub 推送授权待所有者触发**：已初始化本地仓库并关联 `https://github.com/halzioncore/Hoptimization.git`（`user.name=halzioncore`、`user.email=halzioncore@gmail.com`），待所有者在本地终端执行一次 `git push -u origin main --tags` 完成 Git Credential Manager 浏览器登录授权。

---

## 2026-11-17 · 初始化项目骨架与 Git 仓库

- **做了什么**：搭建 Minecraft 1.8.9 Forge (`11.15.1.2318`) + Architectury Loom (`0.10.0.5`) + Mixin (`0.7.11`/`0.8.5`) 项目骨架，完成 Gradle 8.8 包装器、游戏资源 (`downloadAssets`) 与反编译源码 (`genSources`) 缓存；初始化 Git `main` 分支、设置仓库级署名与 `origin` 远程地址，并补齐 `.gitignore` 中的 `out/`。
- **改动文件**：`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`gradle/wrapper/*`、`gradlew*`、`log4j2.xml`、`README.md`、`.gitignore`、`.gitattributes`、`src/main/java/com/hoptimization/*`、`src/main/resources/*`、`docs/agent必看/PROGRESS.md`
- **验证**：`.\gradlew.bat build --offline` ✅，`runClient` 未运行
- **Git**：分支 `main` 初始提交，标签 `v0.1.0-init`，推送 ❌（待所有者首次授权后执行 `git push -u origin main --tags`）
- **回退到上一版本**：无（仓库初始提交）
- **已知问题**：本机直连 GitHub / Forge Maven 可能触发 TLS 握手重置，若推送失败需配置 Git 代理或开启代理 TUN 模式。
- **下一步**：所有者执行 `git push -u origin main --tags` 完成首次授权与备份后，按 `03_ClickGUI实现提示词.md` 从 `main` 切出功能分支开始开发。

---

## 2026-11-17 · 规范修订

- **做了什么**：功能范围改为由所有者决定、不设限；美术规范改为多主题系统（6 个内置主题 + 完全可自定义主题 + 主题编辑器 + 导入导出）；新增 `04_版本管理规范.md`（Git 分支/标签/推送/回退）。
- **未做**：未实现任何功能代码；仓库尚未 `git init`。

---

## 2026-11-17 · 规范制定

- **做了什么**：建立 `docs/agent必看/` 规范体系（开发规范、美术规范、ClickGUI 实现提示词）。
- **未做**：未实现任何功能代码。
- **下一步**：按 `03_ClickGUI实现提示词.md` 实现 UI 基础设施 + ClickGUI 框架（含模块系统骨架，模块用占位数据）。
