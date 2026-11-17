# PROGRESS · 进度日志

> 新记录追加在**最上方**。格式见 `01_开发规范.md §12`。

## 待决策

- 暂无（`build.gradle.kts` 已通过新增 `launchClient` `JavaExec` 任务解决 Gradle 8.8 + Loom `0.10.0.5` 启动报错；若后续升级 Loom 可切回 `runClient`）。

---

## 2026-11-17 · ClickGUI 窗口框架、六大分类、独立缩放与作弊模式门禁（v0.4.0）

- **做了什么**：在实测关修复图集 Mipmap（`GlyphAtlas` Level-1 盒式降采样 + 物理像素基线对齐）后，完成 `03_ClickGUI实现提示词.md §11` 第 3 步及架构扩展：
  1. **构建修复**：在 `build.gradle.kts` 新增 Java 8 toolchain `launchClient` (`JavaExec`) 任务并禁用受 Gradle 8.8 `AbstractRunTask.getMain()` 校验限制的 `runClient`；
  2. **六大分类与门禁**：`Category` 重构为 `COMBAT`、`MOVEMENT`（新增，Lucide `wind` 图标）、`VISUAL`、`UI`（由 `HUD` 重命名并兼容读取旧 `"hud"`）、`PERFORMANCE`、`MISC`；`Module` 新增 `toggleable` 与 `requiresCheatMode` / `isAvailable()`，`ModuleManager` 在快捷键与触发路径执行门禁；
  3. **核心系统模块**：新增 `UI -> ClickGuiModule`（`clickgui`，不可开关配置模块，默认快捷键 `RSHIFT`，`scale` `0.75–2.0` 步进 `0.05` 默认 `1.25x`）与 `UI -> CheatMode`（`cheat_mode`，默认关闭，关闭时自动关闭全部作弊模块并持久化）；将 `CpsDisplay`/`TargetHud` 迁移至 `modules/ui/`；
  4. **窗口框架与独立缩放**：完成 `ClickGuiScreen` (`720×440`)、`ClickGuiSidebar` (`192` 宽、品牌区、搜索框外观、滑动选中指示条、底部配置档与设置入口)、`ClickGuiContentHeader`、13 枚 Lucide 单色图标、`UiConfig.clickGuiScale` 双向同步及 ClickGUI 内 `Ctrl + 滚轮` ±0.05× 缩放。
- **改动文件**：`build.gradle.kts`、`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/{module,config}/*`、`modules/ui/*`、`ui/{clickgui,font,render}/*`、`src/main/resources/assets/hoptimization/{lang,texture/icon}/*`、`docs/agent必看/*`
- **验证**：`.\gradlew.bat clean build --offline` ✅；`.\gradlew.bat launchClient`（首次联网缓存 `:runtimeClasspath`）与 `.\gradlew.bat launchClient --offline` ✅（`Hoptimization 0.4.0` 初始化 11 模块与 Mixin 正常加载，无 GL/Mixin 错误）；第 3 步框架及中英混排 4 张 1080p 截图目视确认通过
- **Git**：分支 `feat/clickgui-frame` (`2272fa5`) `--no-ff` 合入 `main` (`dc26e84`)，同步三处版本号 `0.4.0`，标签 `v0.4.0`
- **回退到上一版本**：`git revert -m 1 dc26e84`（或 `git checkout -b fix/rollback v0.3.0`）
- **已知问题**：卡片列表区（`ui/component/` 控件与 `ModuleCard`）仍按第 4 步规划留空，待 `v0.5.0` 接入。
- **下一步**：并行推进 Wave 2：`v0.5.0`（合入 `feat/ui-components` 并实现 `ModuleCard` 与作弊锁定态）与 `v0.6.0`（Combat / Movement+Visual+Perf+Misc / UI-HUD 功能模块集群）。

---

## 2026-11-17 · 模块系统骨架、渲染基础设施与字体主题系统（v0.3.0）

- **做了什么**：通过 3 人独立工作副本与独立分支并行开发并依次合入 `main`，完成 `03_ClickGUI实现提示词.md §11` 第 1–2 步：
  1. `feat/module-system`：实现 `Category`、`Module`、`ModuleManager`、五种 `Setting`（含 `visibleWhen`）、`UiConfig` 与容错 `ConfigManager`（原子写盘、500ms 防抖异步保存、配置档管理）、9 个占位模块及中英文语言文件（`en_US.lang`、`zh_CN.lang`）；
  2. `feat/render-core`：实现 GLSL 120 SDF 圆角矩形与柔和阴影着色器、三角形扇+边缘羽化回退、`RenderUtil`、`ScissorStack`、`UiScale` 及帧率无关的 `Easing`/`AnimDuration`/`Animation`/`ColorAnimation`；
  3. `feat/font-theme`：引入静态 Inter TTF 字体（SIL OFL）与 2× 超采样 AWT/GL 图集字体渲染器（中文系统字体回退、宽度 LRU 缓存、省略号截断）、`UiTokens`、`ThemeData`、`BuiltinThemes`（6 套内置主题 + 8 个强调色预设）、`ThemeSerializer` 与平滑插值 `ThemeManager`；
  4. `chore/release-v0.3.0`：在 `HoptimizationMod` 串联 UI 状态与主题/缩放初始化，同步三处版本号至 `0.3.0`。
- **改动文件**：`HoptimizationMod.java`、`gradle.properties`、`src/main/java/com/hoptimization/core/{module,setting,config}/*`、`src/main/java/com/hoptimization/modules/{combat,visual,hud,performance,misc}/*`、`src/main/java/com/hoptimization/ui/{render,anim,font,theme}/*`、`src/main/resources/assets/hoptimization/{lang,shader,font}/*`、`docs/agent必看/PROGRESS.md`
- **验证**：合并集成后 `.\gradlew.bat clean build --offline` ✅；各分支独立通过纯 Java 单元测试/自检；`runClient` 未运行（GL 着色器、字体图集与实机渲染待第 3 步接入画面后实测）
- **Git**：分支 `feat/module-system` (`1911cd2`) + `feat/render-core` (`862051e`) + `feat/font-theme` (`80c20c5`) + `chore/release-v0.3.0` (`1c919b5`) → `main`，标签 `v0.3.0`，推送 ✅
- **回退到上一版本**：`git revert -m 1 1c919b5 80c20c5 862051e 1911cd2`（或直接从 `v0.1.0-init` 重建分支 `git checkout -b fix/rollback v0.1.0-init`）
- **已知问题**：`runClient` 尚未运行，OpenGL 着色器编译、SDF 圆角/阴影视觉效果及 TTF 字形图集渲染尚未在游戏窗口内实测。
- **下一步**：按 `03 §11` 第 3 步改为单智能体顺序开发：实现 ClickGUI 窗口框架（打开/关闭动画、侧边栏、分类切换指示条滑动动画、内容头部与滚动裁剪），并执行 `runClient` 实测。

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
