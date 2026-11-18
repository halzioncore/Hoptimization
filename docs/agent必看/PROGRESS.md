# PROGRESS · 进度日志

> 新记录追加在**最上方**。格式见 `01_开发规范.md §12`。

## 待决策

- 暂无（`build.gradle.kts` 已通过新增 `launchClient` `JavaExec` 任务解决 Gradle 8.8 + Loom `0.10.0.5` 启动报错；若后续升级 Loom 可切回 `runClient`）。

---

## 2026-11-18 · 最终击杀触发修复、界面分类中文化与杂项语言切换（v0.8.0）

- **做了什么**：通过 `/swarm` 2 名并行工程师（`final-kill-fix`、`misc-language`）完成修复与新模块开发并集成验证，全仓共注册 **25 个模块**：
  1. **终结击杀检测与多通道兜底 (`feat/final-kill-fix`)**：完善 `KillEffects` 与 `KillEffectsTracker`（`ATTACK_WINDOW_NANOS` 延至 `8s`、32 槽 `recentGlobalHurt` `3s` 全局受击窗口、32 槽 `12s` 玩家快照缓存、16 槽 `1.5s` `PreTeleportCache` 传送前坐标缓存、`EntityFireball`/`EntityTNTPrimed` 归属与近距离追踪、`>= 2s` 重生再击杀去重过期与零热路径分配）；在 `MixinNetHandlerPlayClientVisual` 保留 `handleTimeUpdate` 与 `handleDestroyEntities`（移除 `hurtTime > 0` 限制并新增隐身且不在 TabList 的反作弊假人过滤），同时新增主线程保护且 `require = 0` 的 `handleEntityMetadata`（血量归零）、`handleEntityTeleport`（`deltaSq >= 64.0` 传送前坐标快照）与 `handlePlayerListItem`（转旁观/移除玩家）钩子；接入 `ClientChatReceivedEvent` 中英文终结/普通击杀聊天兜底（词边界、主动/被动方向校验与存活已重生实体保护）。
  2. **杂项语言切换与界面中文化 (`feat/misc-language`)**：新增不可开关配置模块 `LanguageModule`（`id = "language"`，`Category.MISC`，`ModeSetting` 支持 `auto`/`zh_cn`/`en_us`），`preInit` 配置加载期延后至 `HoptimizationMod.init()` 应用、记录并按 `auto` 恢复 `vanillaLanguageCode`、通过 `LanguageManager.onResourceManagerReload` 快速重载语言避免纹理重载卡顿、交互切换时更新字体 unicode/bidi 标志并 `saveOptions()`、递增 `languageRevision`；在 `ModuleManager` 注册至 **25 个模块**；在 `ClickGuiScreen.drawScreen` 检测修订号变化并自动调用 `refreshAllLabels()`；将 `zh_CN.lang` 的 `category.hoptimization.ui` 设为 `界面`，对齐 `en_US.lang` / `zh_CN.lang`（各 217 条，UTF-8 无 BOM），并同步三处版本号至 `0.8.0`。
- **改动文件**：`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/module/ModuleManager.java`、`modules/misc/LanguageModule.java`、`modules/visual/KillEffects.java`、`modules/visual/killeffects/KillEffectsTracker.java`、`mixin/client/MixinNetHandlerPlayClientVisual.java`、`ui/clickgui/ClickGuiScreen.java`、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`、`docs/agent必看/PROGRESS.md`
- **验证**：`.\gradlew.bat clean build --offline` ✅（零错误零警告，生成 `hoptimization-0.8.0.jar` 与 `mixins.hoptimization.refmap.json` 全部 5 项 `MixinNetHandlerPlayClientVisual` 映射）；`.\gradlew.bat launchClient --offline` 启动与模块/Mixin 初始化 ✅（日志确认 `Pre-initializing Hoptimization 0.8.0`、`Hoptimization initialized (25 modules).`、`Hoptimization mixins active.` 且无 `InvalidInjectionException` / `MixinApplyError` / GL 错误）；Hypixel 与落花星雨实战最终击杀与 ClickGUI 语言切换待所有者手测
- **Git**：分支 `feat/final-kill-fix` (`015aceb`) + `feat/misc-language` (`f3d9a77`) → `feat/final-kill-and-lang` (`a5726ee` → `464ff3e`) `--no-ff` 合入 `main`，本地标签 `v0.8.0`，远程推送 ⏸ 未推送（待所有者确认）
- **回退到上一版本**：`git checkout -b fix/rollback v0.7.0`（或从 `v0.8.0` 依次执行 `git revert HEAD && git revert -m 1 464ff3e`）
- **已知问题**：Hypixel 与落花星雨等多人实战环境下的终结击杀特效触发表现与 ClickGUI 实时切换语言体验待所有者手测验收；ClickGUI 设置页的 6 主题预览网格、强调色面板、配置档切换弹窗及主题编辑器仍留待后续版本推进。
- **下一步**：所有者进服手测终结击杀特效与 `Misc -> Language` 切换效果并确认后执行远程推送；后续继续按 `03 §11` 第 5–6 步完善 ClickGUI 设置页。

---

## 2026-11-18 · 击杀效果模块：抽魂斩杀与湮灭（v0.7.0）

- **做了什么**：通过 `/swarm` 3 名并行工程师（`effects-core`、`render-engine`、`mixins-wiring`）及集成/验证完成 Visual 分类新模块 `KillEffects`（`kill_effects`，默认关闭），全仓共注册 **24 个模块**：
  1. **核心模块与击杀追踪 (`feat/kill-effects-core`)**：实现 `KillEffects`、`KillEffectInstance` 与 `KillEffectsTracker`（16 槽位预分配实例池、32 槽近战与投射物归因数组、64 项环形死亡去重缓冲）；支持 `soul_sever`（抽魂斩杀）与 `annihilation`（湮灭）双模式、`targets`（`all`/`players`/`mobs`）、`only_own_kills`、`duration`、`soul_color`、`weapon_scale`、`particles` 与 `sound` 8 项设置，以及击杀瞬间快照姿态/手持武器并抑制原版倒地尸体。
  2. **3D 灵魂着色器与粒子渲染 (`feat/kill-effects-render`)**：新增 GLSL 120 着色器 `kill_soul.vsh` / `kill_soul.fsh`、`KillSoulShader`（支持固定管线多纹理组合器自动回退与 OptiFine `GL_CURRENT_PROGRAM != 0` 旁路保护）、`SoulParticlePool`（256 槽零分配原始数组粒子池）与 `KillEffectsRenderer`（双 Pass 半透明实体渲染、实体背后 3D 武器斩杀动效、3D 弧光与空手几何月牙刃回退、全量 GL 状态还原）。
  3. **Mixin 钩子、注册与本地化 (`feat/kill-effects-mixins`)**：新增 `MixinEntityLivingBaseVisual`、`MixinRenderManagerVisual`、`MixinRendererLivingEntityVisual`、`MixinLayerArmorBaseVisual`（内部通道取消附魔护甲闪烁以防重置混合状态），并在 `MixinNetHandlerPlayClientVisual` 保留 `handleTimeUpdate` 同时接入 `handleDestroyEntities`；全部 Mixin 遵循 zero `@Overwrite`、`require = 0` 与 `hoptimization$` 前缀；在 `ModuleManager` 注册 `KillEffects`（总计 24 模块），对齐中英文语言键（各 211 条，UTF-8 无 BOM），同步版本号至 `0.7.0`。
- **改动文件**：`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/module/ModuleManager.java`、`modules/visual/KillEffects.java`、`modules/visual/killeffects/{KillEffectInstance,KillEffectsTracker,KillEffectsRenderer,KillSoulShader,SoulParticlePool}.java`、`mixin/client/MixinNetHandlerPlayClientVisual.java`、`mixin/render/{MixinEntityLivingBaseVisual,MixinRenderManagerVisual,MixinRendererLivingEntityVisual,MixinLayerArmorBaseVisual}.java`、`src/main/resources/assets/hoptimization/shader/{kill_soul.vsh,kill_soul.fsh}`、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`、`docs/agent必看/PROGRESS.md`
- **验证**：`.\gradlew.bat clean build --offline` ✅（零错误零警告，生成 `hoptimization-0.7.0.jar` 与完整 `mixins.hoptimization.refmap.json`）；`.\gradlew.bat launchClient --offline` 启动与 Mixin 实测加载 ✅（日志确认 `Hoptimization 0.7.0` 初始化 24 模块、`Hoptimization mixins active.` 及 Visual Mixin 加载无 `InvalidInjectionException` / GL 错误；单人世界击杀特效实机画面待所有者手动游玩验收）
- **Git**：分支 `feat/kill-effects-core` (`e6a30e3`) + `feat/kill-effects-render` (`7dddfb4`) + `feat/kill-effects-mixins` (`81bcdf3` → `b5198a5`) → `feat/kill-effects` (`7e815d1` → `8045d34`) `--no-ff` 合入 `main`，本地标签 `v0.7.0`，远程推送 ⏸ 未推送（待所有者确认）
- **回退到上一版本**：`git checkout -b fix/rollback v0.6.0`（或从 `v0.7.0` 依次执行 `git revert HEAD && git revert -m 1 8045d34`）
- **已知问题**：单人/多人实战下击杀特效的动画节奏、粒子密度及与其它客户端 Mod 兼容性待所有者手测验收；ClickGUI 设置页的 6 主题预览网格、强调色面板、配置档切换弹窗及主题编辑器仍留待后续版本推进。
- **下一步**：所有者进游戏手测 `Visual -> Kill Effects` 两种模式效果并确认后执行远程推送；后续按 `03 §11` 第 5–6 步继续完善 ClickGUI 设置页。

---

## 2026-11-17 · 首批战斗 / 移动 / 视觉 / 性能 / 杂项与 HUD 功能模块（v0.6.0）

- **做了什么**：通过 `/swarm` 3 名并行工程师（`combat-modules`、`move-visual-perf`、`ui-hud-modules`）在各自独立工作副本完成 12 个新增功能模块与 11 个既有模块实装并合入集成，全仓共注册 **23 个模块**（按所有者筛选排除 `KeepSprint`、`SafeWalk`、`FreeLook`、`FastPlace`、`AutoRespawn`）：
  1. **Combat (`feat/combat-modules`)**：实装作弊门禁模块 `AutoClicker`、`HitDelayFix`、`WTap`，与非作弊视觉/统计模块 `OldAnimations`、`HitColor`、`ComboCounter` 及 `mixin/combat/*`（含 OptiFine `require = 0` 保护与 zero `@Overwrite`）；
  2. **Movement / Visual / Performance / Misc (`feat/move-visual-perf`)**：实装 `ToggleSprint`（切换/常驻 + 仅世界内安全 `ToggleSneak`）、作弊门禁 `NoJumpDelay`、`LowFire`、`BlockOutline`、`HurtCam`、`Zoom`（默认 `C`，支持 Hold/Toggle 与平滑相机恢复）、`TimeChanger`、`ParticleLimit`、`MouseDelayFix` 与 `Fullbright`；
  3. **UI / HUD (`feat/ui-hud-modules`)**：新增轻量化 `ui/hud/` 覆盖层调度与交互式拖拽编辑界面 `HudEditScreen`（持久化至 `UiConfig.HudLayout`），实装并接入 `CpsDisplay`、`TargetHud`、`FPSDisplay`、`Coordinates`、`Keystrokes`，并在 ClickGUI 设置页接入“编辑 HUD 布局”快捷入口；
  4. **集成发布 (`chore/release-v0.6.0`)**：在 `ModuleManager` 统一注册 23 个模块，合入并核验 196 条中英文 `en_US.lang` / `zh_CN.lang` 全量键对齐（无 BOM），同步三处版本号至 `0.6.0`。
- **改动文件**：`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/module/ModuleManager.java`、`modules/{combat,movement,visual,ui,performance,misc}/*`、`mixin/{combat,client,render}/*`、`ui/{hud,clickgui}/*`、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`、`docs/agent必看/PROGRESS.md`
- **验证**：`.\gradlew.bat clean build --offline` ✅；`VerifyV060Integration`（206 项检查：6 分类计数、23 模块及全部设置项/枚举项中英文键一致性、作弊门禁与禁用模块排除校验）✅；`.\gradlew.bat launchClient --offline` 客户端实测启动 ✅（日志确认 23 模块初始化、Combat/Client/Render 全部 Mixin 加载且无 `InvalidInjectionException`/GL 错误；单人世界逐模块实时动作手测待后续游玩体验微调）
- **Git**：分支 `feat/combat-modules` (`40b386a` → `c2ce906`) + `feat/ui-hud-modules` (`d8007f2` → `9de138d`) + `feat/move-visual-perf` (`41d1dbc` → `1350fed`) + `chore/release-v0.6.0` (`44c5737` → `5d9e7e2`) `--no-ff` 合入 `main`，标签 `v0.6.0`
- **回退到上一版本**：`git checkout -b fix/rollback v0.5.0`（或从 `v0.6.0` 依次执行 `git revert HEAD && git revert -m 1 5d9e7e2 1350fed 9de138d c2ce906`）
- **已知问题**：ClickGUI 设置页的完整 6 主题预览网格、强调色面板、配置档切换弹窗及主题编辑器按规划留待后续 `v0.7.0`；`AutoClicker`/`WTap`/`NoJumpDelay`/`TimeChanger` 的多人服/单人世界手感与细节微调可在下一轮实玩验收后调整。
- **下一步**：按 `03 §11` 第 5–6 步继续完善 ClickGUI 设置页（主题网格、强调色、配置档菜单与主题编辑器）。

---

## 2026-11-17 · ClickGUI 通用组件库、ModuleCard、搜索筛选与作弊置灰门禁（v0.5.0）

- **做了什么**：通过 `/swarm` 独立工作副本（`ui-components` + `clickgui-cards`）合入 `feat/ui-components`（`be7d63b`）与 `feat/clickgui-cards`（`96c2a69`），完成 `03_ClickGUI实现提示词.md §11` 第 4 步核心交互与门禁呈现：
  1. **通用组件库 (`ui/component/`)**：新增 `UiComponent`、`PopupOverlay`、`PopupHost`、`PopupManager`、`SwitchComponent`、`SliderComponent`、`SegmentedComponent`、`DropdownComponent`、`KeybindComponent`、`ColorComponent`、`ColorPickerPopup`、`TextFieldComponent`、`TooltipManager` 与 `TooltipRenderer` 共 14 个零硬编码主题色可复用组件；
  2. **模块卡片 (`ModuleCard`)**：实现 52px 收起态与 180ms 展开高度/透明动画、全部设定行渲染（含 `visibleWhen` 动态显隐）、中键快速绑键（`Esc` 取消/`Delete` 清空）、`!toggleable`（`ClickGuiModule`）无开关配置卡模式，以及 `!isAvailable()` 作弊模式未开启时整体 **40% 不透明度置灰锁定** + `ui.hoptimization.cheat_locked` Tooltip 提示；
  3. **搜索、筛选、滚动与弹出层**：接入侧边栏实时搜索框、`Ctrl+F` / 闲置直接打字触发全局分类分组搜索、`All | Enabled` 筛选、80ms 指数平滑滚动、`ScissorStack` 裁剪、12px 上下渐隐遮罩、800ms 自动淡出 3px 滚动条、顶层 `PopupManager` 与分级 `Esc` 回退。
- **改动文件**：`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`src/main/java/com/hoptimization/ui/component/*`、`src/main/java/com/hoptimization/ui/clickgui/{ModuleCard,ClickGuiSidebar,ClickGuiContentHeader,ClickGuiScreen}.java`、`docs/agent必看/PROGRESS.md`
- **验证**：`.\gradlew.bat clean build --offline` ✅；`.\gradlew.bat launchClient --offline` 客户端实测启动及 ClickGUI 交互、弹出层与作弊门禁 40% 锁态 ✅（`ui-components` 100 项组件逻辑断言 & `clickgui-cards` 9 张实机截图验收通过，合入 `main` 后已再次验证）
- **Git**：分支 `feat/ui-components` (`be7d63b`) → `feat/clickgui-cards` (`96c2a69`) `--no-ff` 合入 `main` (`2dc4082`)，同步三处版本号 `0.5.0`，标签 `v0.5.0`
- **回退到上一版本**：`git revert -m 1 2dc4082`（或 `git checkout -b fix/rollback v0.4.0`）
- **已知问题**：设置页卡片内容（主题切换网格、强调色选择与“编辑 HUD 布局”按钮跳转）与主题编辑器留待后续专题版本接入；首批真实功能模块合入见 `v0.6.0`。
- **下一步**：合入 Wave 2 并行分支（`feat/combat-modules`、`feat/move-visual-perf`、`feat/ui-hud-modules`），统一接入 `ModuleManager`、中英文语言键与 Settings HUD 编辑器入口，完成实测后发布 `v0.6.0`。

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
