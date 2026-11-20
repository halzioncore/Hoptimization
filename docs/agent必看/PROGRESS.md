# PROGRESS · 进度日志

> 新记录追加在**最上方**。格式见 `01_开发规范.md §12`。

## 待决策

- 暂无（`build.gradle.kts` 已通过新增 `launchClient` `JavaExec` 任务解决 Gradle 8.8 + Loom `0.10.0.5` 启动报错；若后续升级 Loom 可切回 `runClient`）。

---

## 2026-11-20 · 贾维斯阵营/队友识别修复与道路虚空坑洞提示（v0.13.0）

- **做了什么**：针对 Hypixel 起床战争实战反馈完成 2 项阵营识别 Bug 修复与 1 项道路虚空坑洞防坠落提示功能，零新增 Mixin，版本号同步至 `0.13.0`：
  1. **修复队友误报为入侵者（Bug 1）**：改进 `BedWarsContext.extractLastColorFromFormattedText` 与新增 `extractColorBeforeName`（在 `[123✫]` 等等级前缀之后、玩家名之前提取最终颜色，并忽略玩家名后的 `[GUILD]` 后缀）、新增 `resolvePlayerTeamColor(EntityPlayer, Minecraft)` 串联 `ScorePlayerTeam` / TabList `NetworkPlayerInfo` / `registeredName` / `Slot 2-3` 染色皮革盔甲，并在 `isValidEnemyPlayer(target, local, mc, localTeam, presentTeamsMask)` 中补充 TabList `GameType.SPECTATOR` 重生幽灵过滤、同前缀比对、胸甲/头盔同 RGB 皮革比对与在场队伍掩码过滤；同步更新 `BaseIntrusionMonitor` 传入上下文队伍与在场队伍掩码。
  2. **修复己方队伍/灰队/莫名其妙虚构队伍上榜（Bug 2）**：重构 `BedWarsContext.updateMatchDetection` / `scanSidebarRoster` / `parseScoreboardTeamLine`（要求至少 2 条有效队伍状态行才判定 `inBedWarsMatch = true`，过滤仅含 `"BED WARS"` 标题的大厅与等待区，并提取 `presentTeamsMask`、`bedAliveMask` 与 `YOU`/`你` 标记的 `scoreboardLocalTeam`）；放宽 `anchorHomeAt` 对出生点 28m 内被灰/白装饰黏土误投票床位的己方基地锚定；重加权 `voteBedTeamColorFromBlocks`（近床羊毛 `+6`、中性灰/白粘土降为 `+1`、`MIN_BLOCK_VOTE_SCORE = 12` 且仅计入 `isEligibleEnemyColor`）；为 `updateEnemyBedPlayerAssociations` 增加 `playerVotes[]` 投票累积防路过误覆盖，在 `reverifyTrackedBeds` 中按记分板 `bedAliveMask` 同步未加载区块已破床状态并新增 `deduplicateEnemyBedColors()`；在 `Jarvis.isTrackedBedHostile` 中显式排除己方同色或不在当前记分板队伍名单内的床位。
  3. **新增道路虚空坑洞提示（Feature 3）**：新建零分配检测器 `VoidPitDetector`（检测以玩家为中心的 `6..24m` 范围内直接贯通至 `y = 0` 的 1–3 格道路/桥梁断口及 1 格对角桥断口，忽略桥侧大片开阔虚空与下方有方块托底的非虚空浅坑，输出紧贴洞口边缘的 4 向 `pitEdgeMask`）；在 `JarvisWorldRenderer.renderVoidPits` 中使用克制低饱和 `warning`/`danger` 主题 token 与 `throughWalls = false` 深度测试渲染轻度洞口微光+向下渐隐光幕、`1.6F` 轮廓边线与 `1.0F` 警示交叉线，并严格 `try/finally` 恢复 OpenGL 状态；在 `Jarvis` 新增 `void_pits`（`坑洞提示`，默认开启）与 `pit_range`（`坑洞检测范围`，默认 `14.0m`，`6..24m`）。
- **改动文件**：`docs/agent必看/PROGRESS.md`、`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`modules/visual/Jarvis.java`、`modules/visual/jarvis/{BedWarsContext,BaseIntrusionMonitor,VoidPitDetector,JarvisWorldRenderer}.java`、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`
- **验证**：`.\gradlew.bat clean build --offline --console=plain` ✅（`BUILD SUCCESSFUL in 13s`，零错误，生成 `build/libs/hoptimization-0.13.0.jar`，`2384162` 字节）；Zulu JDK 8 集成与字节码校验 ✅（`223` 个 `.class` 全部为 Java 8 major=52、`HoptimizationMod.VERSION == "0.13.0"`、`ModuleManager` 31 模块与 `void_pits`/`pit_range`、`en_US.lang` 与 `zh_CN.lang` 各 `353` 条唯一键无 BOM 100% 对齐、`1921` 项断言全通过覆盖 Hypixel 前缀/Tab 旁观者/同皮甲过滤、大厅记分板隔离、出生点灰粘土锚定、黄羊毛压制灰粘土投票及 1–3 格/对角/浅坑网格识别）；`.\gradlew.bat launchClient --offline --console=plain` ✅（实测确认 `Pre-initializing Hoptimization 0.13.0`、`Hoptimization initialized (31 modules).`、`Hoptimization mixins active.`，零 Mixin/OpenGL 错误，客户端进程已干净终止）
- **Git**：分支 `fix/jarvis-detection-and-void-pits` (`db23148`) `--no-ff` 合入 `main`（`bbc3014`），标签 `v0.13.0`，推送 ❌ 未推送（待所有者确认）
- **回退到上一版本**：`git revert HEAD && git revert -m 1 bbc3014`（或 `git checkout -b fix/rollback v0.12.0`）
- **下一步**：所有者进游戏在 ClickGUI `视觉 -> 贾维斯` 实测队友/阵营识别与 `坑洞提示`（`坑洞检测范围`）体验；确认无误后决定是否推送远程仓库。

---

## 2026-11-19 · 起床战争智能化战术助手「贾维斯 Jarvis」（v0.12.0）

- **做了什么**：通过 `/swarm` 两阶段并行研发（`bedwars-intel`、`tactical-engines`、`fireball-render` 并行产出，`verifier` 双轮审查验证，`integrator` 集成于 `feat/bedwars-jarvis`）新增作弊门禁 Visual 模块 `Jarvis`（`jarvis`，默认关闭，`requiresCheatMode = true`，零新增 Mixin），全仓共注册 **31 个模块**（Combat 7 · Movement 2 · Visual 10 · UI 7 · Performance 2 · Misc 3）：
  1. **对局上下文与基地入侵提醒 (`feat/jarvis-intel`, `88be444`)**：实现 `BedWarsContext`（侧边栏记分板中英文对局识别 + `work_mode = always` 单人测试模式、团队色解析、每 tick 最多 6 区块时间片环形增量扫描 `ExtendedBlockStorage` 并支持最多 16 个床位跨区块持久化缓存与开局/重锚基地绑定、`isValidEnemyPlayer` 过滤商店 NPC `uuid.version() == 2`、假人、队友与旁观者）与 `BaseIntrusionMonitor`（水平 `base_range` + 垂直 `[-10, +40]` 柱状警戒覆盖高空搭路威胁、提取隐身/高空与手持 TNT/火焰弹/镐/斧/剪刀威胁、滞回冷却与客户端提示音/本地聊天提醒）。
  2. **挖掘物理、3D 挖床路径与防线评估 (`feat/jarvis-engines`, `3cb5eeb`)**：实现 `BedMiningMath`（精确 1.8.9 挖掘耗时公式与木/铁/金镐/剪刀/效率/急迫/挖掘疲劳支持，破除黑曜石不假定/不强制钻石镐且无 `+200` 惩罚）、`BedMiningPathfinder`（`12×7×12` 局部网格先执行外部可达空气 BFS 屏蔽蝴蝶防封闭空腔，再以索引二叉最小堆 Dijkstra 结合挖掘耗时与移动接近速度求解最优隧道并输出实时有序步骤）与 `BedDefenseAnalyzer`（4 层壳 BFS 提取黑曜石/末地石/木板/粘土/防爆玻璃/羊毛/水防护并计算 `easeScore` 排序推荐最易突袭队伍）。
  3. **火焰弹爆破预测与 3D 渲染 (`feat/jarvis-fireball-render`, `adea1d7`)**：实现 `FireballBlastPredictor`（`EntityLargeFireball`/`EntitySmallFireball` 弹道碰撞预测 + 预计算 728 射线确定性双置信层核心/边缘爆破模拟，`bedwars` 预设下 `Blocks.glass`/`Blocks.stained_glass` 视为防爆玻璃免疫并阻挡射线，支持 `vanilla` 预设）与 `JarvisWorldRenderer`（零分配 `Tessellator` 批渲染，红/琥珀分级高亮必炸/边缘方块与弹道引导线，主题色高亮挖床 Step 0 与隧道连线并严格 `try/finally` 还原 OpenGL 状态）。
  4. **主题化双 HUD、主模块编排与 v0.12.0 集成 (`feat/bedwars-jarvis`, `0b73d83`)**：实现 `JarvisHudRenderer`（严格遵循 `02_美术规范.md` 读取 `ThemeManager.current()` token，支持 `card`/`text` 样式、`HudEditScreen` 拖拽缩放预览，提供「战术床防雷达 `jarvis_radar`」展示最易突袭目标/防护方块图标/预计破防耗时/入侵路径摘要，以及「基地入侵警报条 `jarvis_alert`」平滑淡入展示入侵威胁）与 `Jarvis`（18 项 `visibleWhen` 分组设置，纯 Forge 事件调度，`onDisable()` 与切世界清空全部缓存）；在 `ModuleManager` 注册为第 **31 个模块**，对齐 `en_US.lang` 与 `zh_CN.lang`（各 **351 条**唯一键，UTF-8 无 BOM），同步 `gradle.properties` 与 `HoptimizationMod.VERSION` 至 `0.12.0`。
- **改动文件**：`docs/agent必看/PROGRESS.md`、`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/module/ModuleManager.java`、`modules/visual/Jarvis.java`、`modules/visual/jarvis/{BedWarsContext,BaseIntrusionMonitor,BedMiningMath,BedMiningPathfinder,BedDefenseAnalyzer,FireballBlastPredictor,JarvisWorldRenderer,JarvisHudRenderer}.java`、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`
- **验证**：`.\gradlew.bat clean build --offline --console=plain` ✅（`BUILD SUCCESSFUL in 13s`，零编译错误与 Mixin 告警，生成 `build/libs/hoptimization-0.12.0.jar`，`2366105` 字节）；Java 8 集成与字节码校验 ✅（Zulu JDK 8：`222` 个 `.class` 全部为 Java 8 major=52、`HoptimizationMod.VERSION == "0.12.0"`、`ModuleManager` 注册 31 模块与 `CheatMode` 门禁、`en_US.lang` 与 `zh_CN.lang` 各 351 条键 100% 对齐、`1033` 项断言全通过覆盖无钻石镐黑曜石计算/蝴蝶防外部空气 BFS/防爆玻璃阻挡/易突袭排序）；`.\gradlew.bat launchClient --offline --console=plain` ✅（实测确认 `Pre-initializing Hoptimization 0.12.0`、`Hoptimization initialized (31 modules).`、`Hoptimization mixins active.`，零 Mixin/OpenGL 异常且客户端进程已干净停止）
- **Git**：分支 `feat/jarvis-intel` (`88be444`) + `feat/jarvis-engines` (`3cb5eeb`) + `feat/jarvis-fireball-render` (`adea1d7`) → `feat/bedwars-jarvis` (`0b73d83`) `--no-ff` 合入 `main`（`ddeb89f`），标签 `v0.12.0`，推送 ❌ 未推送（待所有者确认）
- **回退到上一版本**：`git revert HEAD && git revert -m 1 ddeb89f`（或 `git checkout -b fix/rollback v0.11.0`）
- **已知问题**：Hypixel 实战环境下多地图记分板/防爆玻璃/蝴蝶防挖床路径体验、基地入侵警报条与战术雷达 HUD 观感待所有者进游戏手测验收。
- **下一步**：所有者进游戏开启 `UI -> 作弊模式` 与 `视觉 -> 贾维斯` 手测（单人测试可将 `work_mode` 设为 `always`），确认后执行远程推送。

---

## 2026-11-18 · 锁敌光环、透视名牌、结构化 ESP、CPS 统计修复与中文输入支持（v0.11.0）

- **做了什么**：通过 `/swarm` 3 条特性分支（`feat/input-and-cps`、`feat/target-ring-nametags`、`feat/structural-esp`）由 `integrator-3` 合入集成分支 `feat/v0.11.0-integration`，全仓共注册 **30 个模块**（Combat 7 · Movement 2 · Visual 9 · UI 7 · Performance 2 · Misc 3）：
  1. **AutoClicker CPS 统计修复与 Windows 中文输入支持 (`feat/input-and-cps`, `bc64602`)**：在 `AutoClicker.handleLeftClick` / `handleRightClick` 触发点击后向 `ClickCpsTracker` 记录 LMB/RMB 时间戳；新增默认开启的 Misc 模块 `ChineseInput`（`chinese_input`，含 `show_composition_bar`、`enable_in_chat`、`enable_in_clickgui` 开关并重写 `resetToDefaults()` 保持默认启用）与 `ImeInputBridge`（非模态透明 `JDialog` + `JTextField` 承接 `InputMethodEvent` 与快捷键，支持原版聊天/告示牌/命令方块/未签名书与笔/铁砧/创造搜索栏及 `ClickGuiScreen` 搜索框，热路径通过 `ConcurrentLinkedQueue` / `AtomicReference` 传递并在 `DrawScreenEvent.Post` 渲染跟随主题的拼音预编辑条）；扩展 `TextFieldComponent`、`ClickGuiSidebar` 与 `ClickGuiScreen` 搜索输入及零分配坐标接口。
  2. **锁敌光环 TargetRing 与透视名牌 Nametags (`feat/target-ring-nametags`, `75c69dd`)**：新增 Combat 模块 `TargetRing`（`target_ring`，单目标近战锁定、260ms 阻尼受击脉冲与可选波纹、`pulse_ring`/`blade_orbit`/`scan_pillar` 三种样式、预计算 `SIN_TABLE`/`COS_TABLE` 零热路径分配及 `try/finally` GL 状态还原）；新增作弊门禁 Visual 模块 `Nametags`（`nametags`，`requiresCheatMode = true`，3D Billboard 透视名牌卡片、距离缩放、血量语义着色与细血条、护甲/手持物品 `RenderItem` 渲染、预分配实体/装备缓冲与可复用 `StringBuilder`）；扩展 `MixinRendererLivingEntityVisual.hoptimization$onCanRenderName` 接入 `Nametags.shouldSuppressVanillaNametag(entity)` 抑制原版重叠名牌。
  3. **结构化克制风格 ESP 透视 (`feat/structural-esp`, `e1fe669`)**：新增作弊门禁 Visual 模块 `Esp`（`esp`，`requiresCheatMode = true`，13 项设置含 `players`/`mobs`/`beds`/`chests`/`entity_style` 等）、零分配增量床扫描缓存 `BedStructureCache`（`BlockPos.MutableBlockPos` 扫描并配对床头/床尾与 4 向朝向）与 `EspRenderer`（折角框/简洁全框实体轮廓、血条、普通/陷阱/末影箱锁扣特征、床架/床垫/被沿线/枕头结构线与严格 GL 状态恢复）。
  4. **集成、注册与 v0.11.0 本地化 (`feat/v0.11.0-integration`)**：在 `ModuleManager` 按分类注册 `TargetRing`、`Nametags`、`Esp`、`ChineseInput` 至 **30 个模块**；验证 `ChineseInput` 与 `ConfigManager` 默认开启及配置持久化无冲突、`ClickGuiScreen`/`ClickGuiSidebar` 版本号动态读取 `HoptimizationMod.VERSION`；补齐并 100% 对齐 `en_US.lang` 与 `zh_CN.lang`（各 **292 条**唯一键，UTF-8 无 BOM，含 `TargetRing` = `锁敌光环` / `Target Ring`）；同步 `gradle.properties` 与 `HoptimizationMod.VERSION` 至 `0.11.0`。
- **改动文件**：`docs/agent必看/PROGRESS.md`、`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/module/ModuleManager.java`、`mixin/render/MixinRendererLivingEntityVisual.java`、`modules/combat/{AutoClicker,TargetRing}.java`、`modules/misc/ChineseInput.java`、`modules/visual/{Esp,Nametags}.java`、`modules/visual/esp/{BedStructureCache,EspRenderer}.java`、`ui/clickgui/{ClickGuiScreen,ClickGuiSidebar}.java`、`ui/component/TextFieldComponent.java`、`ui/input/ImeInputBridge.java`、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`
- **验证**：`.\gradlew.bat clean build --offline --console=plain` ✅（`BUILD SUCCESSFUL in 11s`，零编译错误与警告，生成 `build/libs/hoptimization-0.11.0.jar`，`2259880` 字节）；Java 8 集成与字节码校验 ✅（Zulu JDK `1.8.0_504`：`HoptimizationMod.VERSION == "0.11.0"`、`ModuleManager` 注册 30 模块、`en_US.lang` 与 `zh_CN.lang` 各 292 条唯一键 100% 对齐、`ChineseInput` 默认启用与 `ConfigManager` 持久化、全部 202 个已编译 `.class` 为 Java 8 major=52、`BedStructureCache` 与 `ClickCpsTracker` 校验通过）；`.\gradlew.bat launchClient --offline --console=plain` ✅（`run/logs/latest.log` 实测确认 `Pre-initializing Hoptimization 0.11.0`、`Hoptimization initialized (30 modules).`、`Hoptimization mixins active.`，无 `InvalidInjectionException`/`MixinApplyError`/OpenGL 异常，且客户端与守护进程已干净终止）
- **Git**：分支 `feat/input-and-cps` (`bc64602`) + `feat/target-ring-nametags` (`75c69dd`) + `feat/structural-esp` (`e1fe669`) → `feat/v0.11.0-integration` (`b2ab90e`) `--no-ff` 合入 `main`（`5611809`），标签 `v0.11.0`，推送 ❌ 未推送（待所有者确认）
- **回退到上一版本**：`git revert HEAD && git revert -m 1 5611809`（或 `git checkout -b fix/rollback v0.10.0`）
- **已知问题**：游戏世界内 `ChineseInput` Windows 拼音实机输入与候选上屏、`TargetRing` 三风格锁敌光环击打手感、`Nametags` 卡片透视与护甲渲染、`Esp` 结构化实体/箱子/床透视以及 `AutoClicker` CPS 实时联动待所有者手测验收；ClickGUI 设置页 6 主题预览网格与主题编辑器留待后续版本推进。
- **下一步**：等待所有者进游戏手测 `v0.11.0` 四项新功能与 CPS 修复并确认后执行远程推送；后续继续完善 ClickGUI 设置页。

---

## 2026-11-18 · 击杀效果新模式（幻影剑 / 深渊噬魂）与模组独立语言修复（v0.10.0）

- **做了什么**：通过 `/swarm` 2 名并行工程师（`kill-effects-modes`、`mod-language-fix`）与 `@verifier-2` 交叉审查完成 `KillEffects` 双新模式及模组独立语言切换修复集成，全仓保持 **26 个模块**：
  1. **KillEffects 幻影剑与深渊噬魂模式 (`feat/kill-effects-modes`)**：在 `KillEffects` 与 `KillEffectInstance` 新增 `MODE_PHANTOM_SWORDS`（`phantom_swords`）与 `MODE_ABYSS_VORTEX`（`abyss_vortex`），扩展 `mode` 至 4 项并更新 `soulColor`/`weaponScale` 的 `visibleWhen` 条件（`soul_sever` 与 `phantom_swords` 下可见）；在 `KillEffectsRenderer` 实现 `renderPhantomSwords`（头顶召唤光环、6 柄 3D 幻影剑环绕旋转与交错俯冲刺入、穿刺闪光/音效与迸发粒子）及 `renderAbyssVortex`（地面黑洞传送门、4 条螺旋吸积旋臂与 8 条引力流线、实体旋转收束下沉、`CLIP_PLANE_BUF` 配合 `GL_CLIP_PLANE0` 平面裁剪与 `finally` 还原、坍缩音效与持续吸入粒子）；在 `kill_soul.vsh` 写入 `gl_ClipVertex = eyePos4;`，并在 `SoulParticlePool` 新增 `KIND_ABYSS_VORTEX`（预分配 `targetX/Y/Z` 数组）、向心螺旋更新与新粒子生成方法，全热路径零分配。
  2. **模组独立语言切换修复与 v0.10.0 本地化 (`feat/mod-language-fix`)**：重构 `LanguageModule`，移除全部对原版 `LanguageManager`、`mc.gameSettings.language`、字体标志与 `saveOptions()` 的改动，改为静态加载 UTF-8 词典 `ZH_CN_TABLE`/`EN_US_TABLE`，提供只读 `getEffectiveLanguageCode()`、零分配 `format(String)`、`format(String, Object...)` 与仅同步修订号的 `applyConfiguredLanguageOnInit()`；将模组内 11 个文件中的 `I18n.format` 统一替换为 `LanguageModule.format`（`ClickGuiScreen` 同时追踪 `languageRevision` 与 `effectiveLanguageCode` 响应 `auto` 模式变化，`HudRenderHelper` 同步接入）；在 `zh_CN.lang` 与 `en_US.lang` 新增 `phantom_swords`/`abyss_vortex` 选项键并更新描述（各 242 条唯一键，UTF-8 无 BOM），同步 `gradle.properties` 与 `HoptimizationMod.VERSION` 至 `0.10.0`。
- **改动文件**：`docs/agent必看/PROGRESS.md`、`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/module/{Category,Module}.java`、`core/setting/{KeybindSetting,ModeSetting,Setting}.java`、`modules/misc/LanguageModule.java`、`modules/visual/KillEffects.java`、`modules/visual/killeffects/{KillEffectInstance,KillEffectsRenderer,SoulParticlePool}.java`、`ui/clickgui/{ClickGuiContentHeader,ClickGuiScreen,ClickGuiSidebar,ModuleCard}.java`、`ui/component/KeybindComponent.java`、`ui/hud/HudRenderHelper.java`、`src/main/resources/assets/hoptimization/shader/kill_soul.vsh`、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`
- **验证**：`.\gradlew.bat clean build --offline --console=plain` ✅（`BUILD SUCCESSFUL in 12s`，零编译错误与零 Mixin 警告，生成 `build/libs/hoptimization-0.10.0.jar`，`2172023` 字节）；Java 8 集成校验 ✅（Zulu JDK `1.8.0_504`：`HoptimizationMod.VERSION` 与 `gradle.properties` 均为 `0.10.0`、`ModuleManager` 注册 26 模块、`KillEffects.mode` 含 4 项且 `soul_color`/`weapon_scale` 在 `soul_sever`/`phantom_swords` 下可见而在 `annihilation`/`abyss_vortex` 下隐藏、`LanguageModule` 切 `zh_cn`/`en_us` 分别返回 `"视觉"`/`"Visual"` 且字节码不含原版语言修改符号、`zh_CN.lang` 与 `en_US.lang` 各 242 条键 100% 对齐且无 UTF-8 BOM）；`.\gradlew.bat launchClient --offline --console=plain` ✅（日志确认 `Pre-initializing Hoptimization 0.10.0`、`Hoptimization initialized (26 modules).`、`Hoptimization mixins active.`，无 `InvalidInjectionException`、`MixinApplyError` 或致命异常；游戏内幻影剑/深渊噬魂特效画面与 ClickGUI/HUD 独立语言切换待所有者手测）
- **Git**：分支 `feat/kill-effects-modes` (`fa9f47a`) + `feat/mod-language-fix` (`0de077d`) → `feat/v0.10.0-effects-lang` (`62a8936`) `--no-ff` 合入 `main` (`4661211`)，标签 `v0.10.0`，推送 ❌ 未推送（待所有者确认）
- **回退到上一版本**：`git revert HEAD && git revert -m 1 4661211`（或 `git checkout -b fix/rollback v0.9.0`）
- **已知问题**：游戏世界内 `KillEffects` 的 `phantom_swords` 与 `abyss_vortex` 实时渲染/粒子/剪裁表现及 `Misc -> Language` 仅切换模组界面且不影响原版语言的实机交互待所有者手测验收；ClickGUI 设置页 6 主题预览网格与主题编辑器留待后续版本推进。
- **下一步**：等待所有者进游戏手测 `Visual -> Kill Effects`（幻影剑 / 深渊噬魂）与 `Misc -> Language` 独立切换效果并确认后执行远程推送；后续继续完善 ClickGUI 设置页。

---

## 2026-11-18 · 10 套视觉主题系统与 Visual 主题模块（v0.9.0）

- **做了什么**：通过 `/swarm` 2 名并行工程师（`theme-palettes`、`visual-theme-module`）与 `@verifier` 交叉审查完成 10 套内置视觉主题系统及 Visual 分类主题切换模块集成，全仓共注册 **26 个模块**：
  1. **10 套内置视觉主题与默认黑曜流金升级 (`feat/theme-palettes`)**：在 `BuiltinThemes` 按序定义并注册 `OBSIDIAN_GOLD`（`obsidian_gold` 黑曜流金，新默认主题）、`CYBER_PULSE`、`SPRING_BLOSSOM`、`ABYSSAL_CYAN`、`CRIMSON_ECLIPSE`、`EMERALD_GROVE`、`SUNSET_AMBER`、`STARLIGHT_INDIGO`、`ARCTIC_FROST`、`LAVENDER_MIST` 共 10 套主题（7 深色 + 3 浅色），全部满足 WCAG `textPrimary >= 4.5` 与 `textSecondary >= 3.0`；保留 6 个旧常量别名及 `BY_ID`/`findById`/`isBuiltin`/`ThemeManager.getTheme` 向后兼容映射而不重复加入 `ALL`；将 `UiConfig.DEFAULT_THEME_ID` 与 `ThemeManager` 默认/回退主题升级为 `obsidian_gold`，同步更新 `docs/agent必看/02_美术规范.md §2.3`。
  2. **Visual 主题模块与 0.9.0 本地化 (`feat/visual-theme-module`)**：新增不可开关配置模块 `Theme`（`id = "theme"`，`Category.VISUAL` 首位，`toggleable = false`），内置 10 项 `ModeSetting`（默认 `obsidian_gold`），支持 `Theme.syncOnStartup(uiConfig)` 启动期 `applyImmediate` 同步、运行期切换调用 `ThemeManager.setTheme(...)` 平滑过渡并防抖保存 `UiConfig`、以及 `ModuleManager.handleKeyPress` 绑键循环切换；在 `ClickGuiScreen` 默认展开 `Theme` 卡片；对齐 `en_US.lang` 与 `zh_CN.lang`（各 240 条唯一键，UTF-8 无 BOM），并同步 `gradle.properties`、`HoptimizationMod.VERSION` 与语言文件版本号至 `0.9.0`。
- **改动文件**：`docs/agent必看/02_美术规范.md`、`docs/agent必看/PROGRESS.md`、`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/config/UiConfig.java`、`core/module/ModuleManager.java`、`modules/visual/Theme.java`（新增）、`ui/clickgui/ClickGuiScreen.java`、`ui/theme/{BuiltinThemes,ThemeManager}.java`、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`
- **验证**：`.\gradlew.bat clean build --offline --console=plain` ✅（`BUILD SUCCESSFUL in 11s`，零编译错误与零 Mixin 警告，生成 `build/libs/hoptimization-0.9.0.jar`，`2157628` 字节）；Java 8 集成校验 ✅（Zulu JDK `1.8.0_504`：`BuiltinThemes.ALL.size() == 10`、全部 10 套主题主/次文本 WCAG 对比度达标、`UiConfig.DEFAULT_THEME_ID` 与 `ThemeManager.current().getId()` 均为 `obsidian_gold`、`ModuleManager` 注册 26 模块、`Category.VISUAL` 首位 `theme` 且 `isToggleable() == false`、`Theme.getThemeSetting().getModeCount() == 10`）；`.\gradlew.bat launchClient --offline --console=plain` ✅（日志确认 `Pre-initializing Hoptimization 0.9.0`、`Hoptimization initialized (26 modules).`、`Hoptimization mixins active.`，无 `InvalidInjectionException`、`MixinApplyError` 或致命异常；ClickGUI 实机配色观感与热键切换待所有者手测）
- **Git**：分支 `feat/theme-palettes` (`4ac8736`) + `feat/visual-theme-module` (`b84a7b5`) → `feat/visual-themes` (`6eaa9e1`) `--no-ff` 合入 `main` (`064f2c7`)，标签 `v0.9.0`（原本地未推送的旧 ESP 实验标签 `v0.9.0` 已归档为 `v0.9.0-esp`），推送 ❌ 未推送（待所有者确认）
- **回退到上一版本**：`git revert HEAD && git revert -m 1 064f2c7`（或 `git checkout -b fix/rollback v0.8.0-rollback`）
- **已知问题**：游戏内 ClickGUI 中 10 套主题配色观感、实时平滑过渡动画与绑键循环切换体验待所有者手测验收；ClickGUI 设置页 6 主题预览网格与主题编辑器按规划留待后续版本推进。
- **下一步**：等待所有者进游戏手测 `Visual -> Theme` 切换效果并确认后执行远程推送；后续继续完善 ClickGUI 设置页。

---

## 2026-11-18 · 按所有者要求将源码回退至 v0.8.0（移除 ESP 相关改动）

- **做了什么**：按所有者反馈，通过非破坏性回退在保留完整 Git 历史与过往日志的前提下，将 `src/` 与 `gradle.properties` 精确恢复至 `v0.8.0`（`a5431e2`）状态，全部移除 `v0.9.0` 与 `v0.9.1` 引入的 `Esp` 模块、`EspRenderer`/`EspSketchShader`/`esp_sketch.{vsh,fsh}`、`MixinRenderItemVisual`、`MixinItemRendererVisual` 及其在 `MixinRendererLivingEntityVisual`、`MixinLayerArmorBaseVisual`、`ModuleManager`（恢复 **25 个模块**）与 `en_US.lang`/`zh_CN.lang`（恢复 **217 条** 对齐键）中的全部改动，版本号三处恢复为 `0.8.0`。
- **改动文件**：`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/module/ModuleManager.java`、`modules/visual/Esp.java`（删）、`modules/visual/esp/*`（删）、`mixin/render/{MixinItemRendererVisual,MixinRenderItemVisual}.java`（删）、`mixin/render/{MixinRendererLivingEntityVisual,MixinLayerArmorBaseVisual}.java`、`src/main/resources/assets/hoptimization/shader/esp_sketch.{vsh,fsh}`（删）、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`、`docs/agent必看/PROGRESS.md`
- **验证**：`git diff v0.8.0 -- ':!docs/agent必看/PROGRESS.md'` 零差异 ✅；`.\gradlew.bat clean build --offline --console=plain` ✅（生成 `hoptimization-0.8.0.jar`，`2152332` 字节）；`.\gradlew.bat launchClient --offline --console=plain` ✅（日志确认 `Pre-initializing Hoptimization 0.8.0`、`Hoptimization initialized (25 modules).`、`Hoptimization mixins active.`，零异常）
- **Git**：分支 `fix/rollback-v0.8.0` (`1f5aecf` → `404a4cb`) `--no-ff` 合入 `main` (`ea55673`)，本地标签 `v0.8.0-rollback`（`v0.8.0` 原标签保持不变），远程 `git push` ⏸ 未推送（待所有者确认）
- **复原或回退**：如需再次回到 `v0.8.0` 之前保留的最后一次实验快照，可用 `git checkout -b feat/esp-experiment v0.9.1`；回退本次合入可执行 `git revert HEAD && git revert -m 1 ea55673`。
- **已知问题**：`v0.8.0` 原功能基线完整保留；后续若需重新设计其它样式功能可基于当前 `main`（源码等同 `v0.8.0`）切新分支推进。
- **下一步**：等待所有者确认后续方向或远程推送指令。

---

## 2026-11-18 · 极简亮度单色块与 1px 线稿风格 ESP 重构（v0.9.1）

- **做了什么**：通过 `/swarm` 2 名并行工程师（`esp-sketch-render`、`esp-sketch-module`）与集成/双轮验证完成 `Esp` 极简视觉重构（`esp`，`Category.VISUAL`，默认关闭，`requiresCheatMode = true`，全仓保持 **26 个模块**）：
  1. **4 档亮度单色块着色器与双通道 1px 线稿渲染器 (`fix/esp-sketch-render`)**：删除 v0.9.0 机甲几何与着色器 `EspGeometry`、`EspMechaShader`、`esp_mecha.{vsh,fsh}`；新增 GLSL 120 着色器 `esp_sketch.{vsh,fsh}` 与 `EspSketchShader`（`#8B7CFF` 静谧紫默认配色、4 档亮度单色照片色块 / 平滑渐变切换、穿墙柔光衰减、光影 `GL_CURRENT_PROGRAM != 0` 旁路与固定管线多纹理组合器单次告警回退）；重写 `EspRenderer` 与解耦策略接口 `EspTargetPolicy`（Pass 1 `GL_GREATER` 穿墙通道与 Pass 2 `GL_LEQUAL` 深度预写 + `GL_EQUAL` 实色通道、`isInvisible()` 直通 `GL_LEQUAL` 保留 0.15 透明、1px `ModelBiped` 6 长方体骨架并适配胸甲双臂及护甲外层外扩、`finally` 全量 GL 状态还原与零热路径分配）。
  2. **ESP 设置精简、Mixin 守护与 0.9.1 本地化 (`fix/esp-sketch-module`)**：将 `Esp` 精简为 `color`、`through_walls`、`fill_opacity`、`line_art`、`line_width`（`visibleWhen(lineArt)`）、`posterize`、`ignore_teammates`、`filter_bots`、`render_self` 共 9 项设置并保持 `CheatMode` 门禁与零分配目标/队友/假人过滤；新增 `MixinRenderItemVisual` 与 `MixinItemRendererVisual` 并扩展 `MixinRendererLivingEntityVisual` / `MixinLayerArmorBaseVisual`（仅于世界通道抑制原版实体渲染并保留名牌/阴影/`GuiInventory` 纸娃娃、抑制附魔闪光并守卫手持物品与半透明方块 `blend`/`alphaFunc`/`depthMask` 状态防止泄漏至披风/箭矢/头颅层）；对齐 `en_US.lang` / `zh_CN.lang`（各 **228 条**，UTF-8 无 BOM），并同步三处版本号至 `0.9.1`。
- **改动文件**：`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`modules/visual/Esp.java`、`modules/visual/esp/{EspTargetPolicy,EspSketchShader,EspRenderer}.java`（删除 `EspGeometry`、`EspMechaShader`）、`mixin/render/{MixinRendererLivingEntityVisual,MixinLayerArmorBaseVisual,MixinRenderItemVisual,MixinItemRendererVisual}.java`、`src/main/resources/assets/hoptimization/shader/{esp_sketch.vsh,esp_sketch.fsh}`（删除 `esp_mecha.{vsh,fsh}`）、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`、`docs/agent必看/PROGRESS.md`
- **验证**：`.\gradlew.bat clean build --offline --console=plain` ✅（零 Mixin 警告，生成 `hoptimization-0.9.1.jar` 与完整 `mixins.hoptimization.refmap.json`）；`.\gradlew.bat launchClient --offline --console=plain` 启动与 `26 modules`/全量 Mixin 加载 ✅（日志确认 `Pre-initializing Hoptimization 0.9.1`、`Hoptimization initialized (26 modules).`、`Hoptimization mixins active.` 及 `MixinRenderItemVisual` / `MixinItemRendererVisual` / `MixinRendererLivingEntityVisual` / `MixinLayerArmorBaseVisual` 加载无错误；游戏世界内单色四档照片色块、1px 线稿与穿墙柔和效果待所有者手测）
- **Git**：分支 `fix/esp-sketch-render` (`c6c2b81` → `3e4a194`、`dd6a06d` → `233eb27`) + `fix/esp-sketch-module` (`6bf237d` → `87e9b87`、`6a85b61` → `35f722d`) → `fix/esp-sketch-redesign` (`104d4a3` → `f6d75c5`) `--no-ff` 合入 `main`，本地标签 `v0.9.1`，远程 `git push` ⏸ 未推送（待所有者确认）
- **回退到上一版本**：`git checkout -b fix/rollback v0.9.0`（或从 `v0.9.1` 依次执行 `git revert HEAD && git revert -m 1 f6d75c5`）
- **已知问题**：游戏世界内 `Esp` 单色四档照片色块、平滑渐变、1px 护甲贴合线稿、手持物品/披风混合及穿墙柔和衰减效果待所有者手测验收；ClickGUI 设置页的 6 主题预览网格、强调色面板、配置档切换弹窗及主题编辑器仍留待后续版本推进。
- **下一步**：所有者进游戏开启 `UI -> Cheat Mode` 与 `Visual -> ESP` 手测单色线稿透视效果并确认后执行远程推送；后续继续按 `03 §11` 第 5–6 步完善 ClickGUI 设置页。

---

## 2026-11-18 · 新增未来机甲座舱风格 ESP 玩家透视模块（v0.9.0）

- **做了什么**：通过 `/swarm` 2 名并行工程师（`esp-renderer`、`esp-module`）及集成/验证完成 Visual 分类作弊门禁模块 `Esp`（`esp`，默认关闭，`requiresCheatMode = true`），全仓共注册 **26 个模块**：
  1. **机甲座舱抽象几何、GLSL 120 着色器与双通道 ESP 渲染器 (`feat/esp-renderer`)**：新增 `EspTargetPolicy`、`EspGeometry`（预计算 8 组倒角关节装甲网格与法线缓冲、`-Z` 额头面罩裂缝与胸腔菱形核心、`+Z` 脊柱凸棱、`default` 4px / `slim` 3px 双手臂规格及脚底战术六边形定位环，`static` 块自检全部 `546` 个三角形外向法线）、`EspMechaShader` 与 `esp_mecha.vsh` / `esp_mecha.fsh`（GLSL 120 菲涅尔边缘发光 + 高度全息扫描线 + 心跳脉冲 + 受伤红闪，含 `GL_CURRENT_PROGRAM != 0` 光影旁路与单次告警固定管线回退），以及 `EspRenderer`（256 槽预分配 `TargetRenderState` 零热路径分配、复刻 1.8.9 `RenderPlayer`/`ModelBiped`/`ModelRenderer` 姿态变换、Pass 1 `GL_GREATER` 穿墙全息态与 Pass 2 `GL_LEQUAL` 可见深度预写/表面着色/高光棱线双通道渲染，以及 `finally` 全量 GL 状态还原）。
  2. **ESP 模块、作弊门禁、零分配配色与 0.9.0 本地化 (`feat/esp-module`)**：新增 `Esp`（`Category.VISUAL`，`toggleable = true`，`requiresCheatMode = true`），提供 `style`（`cockpit`/`overlay`/`wireframe`）、`through_walls`、`color_mode`（`custom`/`health`/`team`）、`visible_color`、`hidden_color`、`fill_opacity`、`line_width`、`scanlines`、`ground_ring`、`ignore_teammates`、`filter_bots`、`render_self` 共 12 项设置与 `visibleWhen` 动态显隐；`onRenderPlayerPre` 仅在 `cockpit` 且 `renderManager.isRenderShadow()` 为真时抑制世界内原版玩家渲染（保留背包纸娃娃与 `KillEffectsRenderer` 渲染）；`resolveTargetColorArgb` 采用零热路径分配（`ScorePlayerTeam` 前缀/格式与缓存 `getDisplayNameString()` 解析团队色、三段血量插值与穿墙色相/明度衰减），支持 TabList 隐身反作弊假人过滤与队友过滤；在 `ModuleManager` 注册至 **26 个模块**，对齐 `en_US.lang` / `zh_CN.lang`（各 **237 条**，UTF-8 无 BOM），并同步三处版本号至 `0.9.0`。
- **改动文件**：`gradle.properties`、`src/main/java/com/hoptimization/HoptimizationMod.java`、`core/module/ModuleManager.java`、`modules/visual/Esp.java`、`modules/visual/esp/{EspTargetPolicy,EspGeometry,EspMechaShader,EspRenderer}.java`、`src/main/resources/assets/hoptimization/shader/{esp_mecha.vsh,esp_mecha.fsh}`、`src/main/resources/assets/hoptimization/lang/{en_US,zh_CN}.lang`、`docs/agent必看/PROGRESS.md`
- **验证**：`.\gradlew.bat clean build --offline --console=plain` ✅（零错误零警告，生成 `hoptimization-0.9.0.jar`）；`.\gradlew.bat launchClient --offline --console=plain` 启动与 `26 modules`/Mixin 初始化 ✅（日志确认 `Pre-initializing Hoptimization 0.9.0`、`Hoptimization initialized (26 modules).`、`Hoptimization mixins active.` 且零模块/Mixin 异常；游戏世界内机甲座舱渲染、穿墙双态配色与热键/ClickGUI 开关交互待所有者手测）
- **Git**：分支 `feat/esp-renderer` (`dbf0134` → `03a53be`) + `feat/esp-module` (`42fb08c` → `42b1661`、`3575ff9` → `cf22b97`) → `feat/esp` (`17413cf` → `c6b76a1`) `--no-ff` 合入 `main`，本地标签 `v0.9.0`，远程 `git push` ⏸ 未推送（待所有者确认）
- **回退到上一版本**：`git checkout -b fix/rollback v0.8.0`（或从 `v0.9.0` 依次执行 `git revert HEAD && git revert -m 1 c6b76a1`）
- **已知问题**：游戏世界内 `Esp`（`cockpit`/`overlay`/`wireframe`）机甲座舱渲染、GLSL 着色器实机编译、穿墙双态配色与热键/ClickGUI 开关交互待所有者手测验收；ClickGUI 设置页的 6 主题预览网格、强调色面板、配置档切换弹窗及主题编辑器仍留待后续版本推进。
- **下一步**：所有者进游戏开启 `UI -> Cheat Mode` 与 `Visual -> ESP` 手测机甲座舱透视效果并确认后执行远程推送；后续继续按 `03 §11` 第 5–6 步完善 ClickGUI 设置页。

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
