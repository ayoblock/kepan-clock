# 刻盘钟 → Android 完整移植方案

> 版本：v1.5（2026-11-22） · 状态：**P0–P5 六阶段全部已交付（终版 1.6.4(16)，含壁纸四轮精调与实录声样轮）** · 本文是唯一权威看板，其余文档只留版本戳并指向本文。
> 工程根：`android/`（所有 Android 项目文件集中于此，桌面侧 `src/`、`assets/` 只读引用，不改动）。
> 方案文档实际路径：`android/docs/PORTING_PLAN.md`（2026-11-21 用户已移入 docs/）。
> v1.1 变更来源：`diff.md`（用户提交，已核实后合入，原件归档于 `docs/archive/diff-v1.0-to-v1.1.md`）；v1.2 变更来源：§9 五项拍板（见 §9.1）。目录归放纪律见 §10。

---

## 0. 结论先行

| 维度 | 结论 | 依据 |
|---|---|---|
| 可行性 | 高 | 算法层 ~750 行纯标准库代码，自校用例齐全；渲染层为 1472 行 Immediate-Mode 绘图，与 Android Canvas 2D 一一对应 |
| 难度 | 中高 | 工作量集中在渲染 1:1 迁移（约 1.5k 行）与后台常驻机制（前台服务 / 精确闹钟 / 动态壁纸），无不可解技术障碍 |
| 必要性 | 成立（已拍板自用/作品展示） | §9-5 选 1，按全量功能做，不追求分发规模 |

**技术路线一句话**：Kotlin 纯函数移植历算内核 → Android Canvas 2D 自绘盘面 → 可见期 Choreographer 驱动每秒状态更新、报时由精确闹钟触发且音频播放期短暂转前台 → WallpaperService 自带状态与独立 tick 承接壁画模式，配置由 TOML 改为应用内 DataStore。

> **v1.1 架构关键修订**：不再设"永久前台服务 + 每秒 tick"。秒级驱动按运行态拆分，详见 §3.5。

---

## 1. 范围与边界

### 1.1 移植内容（全量）

| 桌面能力 | Android 承接形态 |
|---|---|
| 主盘三重刻度、晷针、八刻副盘、更牌窗、翻牌动画 | 自定义 View + Canvas 自绘，逐帧 invalidate |
| 昼盘/夜盘 30 秒渐变、星空恒星日缓转、四象瓦当 | 同绘制逻辑，Palette 插值改为 Kotlin 数据类插值 |
| 真太阳时、盘面试时、历注气泡（三日/整月）、settings 牌窗 | 同 hit_test 几何 + 触摸事件，气泡为 Canvas 内绘制而非独立 Activity |
| 报时声（辰 init 钟三下 / 辰正鼓二下 / 每刻鼓一声，串行） | MediaPlayer 队列 + OnCompletionListener 串行，200ms 留白 |
| 壁画模式（全屏置底、六秒一换、四角竖题） | WallpaperService.Engine（动态壁纸）+ 静态壁纸快照 |
| 开机自启壁画 | RECEIVE_BOOT_COMPLETED + 前台服务；壁纸由系统托管无需自启 |
| 配置热重载 | DataStore Flow collect，改即生效 |
| `--png` 离屏自检 | JVM 单测 + 仪器测试渲染为 Bitmap，golden 结构性断言；补充内存与性能 profiler 校验（帧耗时、分配速率、峰值堆） |

### 1.2 明确不移植

- GTK4 / PyGObject / Cairo / PangoCairo / tomllib / paplay / gsettings / .desktop 自启项（平台专属，全部替换）。
- 桌面 `--day/--night/--png/--sound-test` CLI：Android 无 CLI，转为调试开关与测试入口。
- 配置文件保留注释、用户手工编辑 TOML：Android 端改为应用内设置，配置键名保持同名以便语义对照。

### 1.3 验证边界（先写清，避免事后扯皮）

1. **像素级一致性不可达**：字体 rasterizer（Pango vs Android）、字库不同，golden 精确比对不成立。几何/命中/数值用 JVM 单测锁死，视觉交真机截图目检。
2. **交互落点无法无人值守**：Wayland 宿主上模拟器指针注入不可靠；设置牌窗拖动、气泡点击等请用户实机确认，文档标注为「人工验证项」。
3. **后台保活受 OEM 限制**：报时与壁纸在部分国产 ROM 上可能被冻结，需在真机验证并记录机型差异。
4. **壁纸 Engine 生命周期系统与 Activity 解耦，但非独立进程**（修正 diff 表述）：`WallpaperService` 是应用声明的 Service，默认运行在**应用进程内**，由系统绑定与调度；其 `Engine` 的创建/可见性/销毁由系统管理，与 Activity 生命周期无关。因此壁纸模块必须自带完整 `ClockState` 副本与独立 tick 驱动，**不依赖** App 主进程服务状态；但不可按「独立进程」假设做跨进程通信设计。
5. **Android 13+ 前台服务可被用户终止**：任务管理器允许用户从通知栏停止运行前台服务的应用，且**停止的是整个应用进程**，不只是服务（官方确认）。因此报时无法保证 100% 后台可靠；产品文档与设置页须写明此约束，不得宣称"永久常驻"。
6. **开机不自恢复报时**：限制根因是**后台启动前台服务受限**（Android 12+）及 Android 15 对 `BOOT_COMPLETED` 启动部分前台服务的进一步禁止，并非静态广播本身被禁。`BOOT_COMPLETED` 接收器只做可选恢复尝试；产品约束：**开机后须用户手动打开一次 App，报时能力才恢复**。

---

## 2. 架构分层与文件映射

### 2.1 分层（v1.2 四模块结构，按 §9-3 拍板）

模块划分的硬约束：**Compose 依赖只在 `:app` 模块**；`:wallpaper` 为独立 Gradle 模块、纯原生 Canvas，从 classpath 上杜绝 Compose 与 Activity 依赖渗透。历算与渲染下沉为两个 JVM/Android library 供两模块共享。

| Gradle 模块 | 类型 | 依赖 Compose | 内容 |
|---|---|---|---|
| `:domain-core` | Kotlin JVM library | 否 | 历算纯函数、ClockState 组装 |
| `:render-core` | Android library | 否 | Palette/Geometry/TextLayout/DialRenderer/PopupRenderer/WallRenderer |
| `:wallpaper` | Android library | **否（硬约束）** | KepanWallpaperService + 独立 tick 驱动 |
| `:app` | Android application | **仅此模块** | ClockActivity（原生 ClockView + ComposeView 平级）、设置页 Compose UI、配置、报时、BootReceiver |

> **禁止把盘面塞进 AndroidView**（§9-3 明确）：ClockView 是 Activity 内容视图中的原生自定义 View，与 ComposeView 在布局中**平级**；ComposeView 只承载设置牌窗 UI，两者通过共享 ViewModel/回调通信。wallpaper 模块不引入任何 Compose 或 Activity 依赖。

```
domain（:domain-core，纯 Kotlin，零 Android 依赖，JVM 可测）
  ├─ ChenCore      ← chen_core.py      时刻→辰/初正/刻、晷针角、生肖
  ├─ SunMath       ← sun.py            NOAA 日出日落、节气、更点、真太阳时
  ├─ LunarData     ← lunar_data.py     201 项月序表 → IntArray
  ├─ Lunar         ← lunar.py          农历/干支/节气日/候/历注/整月格
  └─ ClockState    ← app.py:compute_state  组装渲染状态（t_min/read/geng/blend/...）

render（:render-core，Android Canvas 2D，无 Compose、无 Activity）
  ├─ Palette       ← dial.py:DAY/NIGHT/lerp_pal
  ├─ Geometry      ← dial.py:_pt/_layout/_popup_geom/settings_geom/hit_test（纯函数，可单测）
  ├─ TextLayout    ← dial.py:_text/_text_w（PangoCairo → Paint/StaticLayout）
  ├─ DialRenderer  ← dial.py:_dial_static/_draw_stars/_draw_needle/_dial_side/_panel/...
  ├─ PopupRenderer ← dial.py:_popup_three/_popup_month/_popup_settings
  └─ WallRenderer  ← dial.py:draw_wallpaper/_vtext

wallpaper（:wallpaper，纯原生 Canvas，无 Compose）
  └─ KepanWallpaperService ← WallpaperService.Engine（自带 ClockState + 独立 tick + 三道功耗闸门）

app / infra（:app，唯一引入 Compose 的模块）
  ├─ Config / ConfigRepository   ← app.py:load_config/save_cfg_value（TOML → DataStore）
  ├─ StrikeQueue / StrikePlayer  ← app.py:_StrikeQueue/_play_strike/_ensure_sounds
  ├─ ClockView（原生 View）      ← app.py:ClockWindow 盘面绘制 + 触摸，与 ComposeView 平级
  ├─ SettingsSheet（Compose）    ← 设置牌窗 UI，仅此部分用 Compose
  ├─ ClockActivity               ← 线性布局容器：[ClockView | ComposeView]，两者平级
  ├─ TickScheduler               ← AlarmManager 精确闹钟（唯一报时触发源）+ Activity 内 Choreographer（可见期秒更新）
  ├─ StrikeService               ← 仅报时音频播放期短暂转前台，维护 StrikeQueue；播完退出前台
  └─ BootReceiver                ← RECEIVE_BOOT_COMPLETED（仅可选恢复尝试，不保证自恢复）
```

### 2.2 逐文件移植要点

| 源文件 | 行数 | 目标 | 关键动作 | 风险 |
|---|---:|---|---|---|
| `chen_core.py` | 82 | domain/ChenCore.kt | 11 例自校直接转 JUnit 参数化测试 | 低 |
| `sun.py` | 191 | domain/SunMath.kt | `datetime`→`java.time`，浮点公式逐行照搬，5 项自校转单测 | 低 |
| `lunar_data.py` | 51 | domain/LunarData.kt | 201 个 hex 转 `intArrayOf(...)`，位运算按原样 | 低 |
| `lunar.py` | 427 | domain/Lunar.kt | 春节/闰月/干支/节气/候/连续性自校转单测 + Python 差分语料 | 中 |
| `app.py`（算法段 206–256） | ~50 | domain/ClockState.kt | 昼夜优先级 CLI>配置>日出日落、30 秒渐变、真太阳时 | 中 |
| `dial.py` | 1472 | render/* | 见 §3 逐 API 映射；静态层缓存 ImageSurface → offscreen Bitmap | **高** |
| `app.py`（UI/声音段） | ~700 | app/* | 事件控制器→触摸监听；GLib.timeout→Handler/coroutine；paplay→MediaPlayer | 中高 |

### 2.3 差分测试（保证历算不走样）

以 Python 为 oracle，生成固定语料后由 Kotlin 逐例比对。文本布局几何**新增单测，不做像素 golden**，只比对行高与文本包围盒几何（Pango 与 Android 换行行为允许差异）：

1. `chen_core`：0–1439 分钟逐分钟输出 `read_str/needle_angle/animal` → 1440 行 golden。
2. `sun`：1900–2100 每年抽样 4 个节气日 + 冬至/夏至，输出日出日落/更点/真太阳时。
3. `lunar`：1900-01-31 至 2099-12-31 逐日 `solar_to_lunar/gz_day/hou` 的哈希摘要（Python 侧算一次 SHA-256，Kotlin 侧重算比对），避免提交 7 万行文件。
4. 运行方式：Python 侧脚本生成 `android/app/src/test/resources/golden/*.json`，作为测试资产入库；**release 包脱敏时排除**（测试语料只放 `src/test/`，不进 APK）。

---

## 3. 渲染内核：Cairo → Android Canvas 2D

### 3.1 为什么选原生 Canvas（推荐，待拍板 1）

- `dial.py` 本就是「纯几何计算 + Immediate-Mode 绘图」，无状态机、无 DOM，与 `android.graphics.Canvas` 同构。
- Compose Canvas 需把 1472 行命令式代码重写为声明式重组，风险与工时都更高；WebView + JS 会引入字体与性能问题，且本项目无富文本编辑需求，无收益。
- 结论：**单 Activity + 自定义 View（Canvas 自绘）**，气泡/设置牌窗仍在同一 Canvas 内绘制，不拆 Composable。

### 3.2 API 映射表

| Cairo / PangoCairo | Android 对应 | 注意点 |
|---|---|---|
| `ctx.set_source_rgb / rgba` | `Paint.color` / `alpha` | Palette 为 0–1 浮点，乘 255 转 ARGB |
| `ctx.rectangle + fill` | `drawRect` | — |
| `ctx.arc + stroke` | `drawArc` / `drawCircle` + `Paint.style=STROKE` | 角度起点定义不同，统一走 `_pt()` 换算后的坐标 |
| `move_to/line_to/curve_to/close_path` | `Path.moveTo/lineTo/cubicTo/close` | 贝塞尔控制点顺序一致 |
| `ctx.save/restore/translate/scale` | `Canvas.save/restore/translate/scale` | 务必成对，渐变期全量重绘时尤其 |
| `set_source_surface + paint` | `drawBitmap` | 静态层缓存见 3.3 |
| `PangoCairo.create_layout / set_font_description` | `TextPaint` + `StaticLayout` | 字体链需自己实现 fallback（见 3.4） |
| `layout.get_width` | `paint.measureText` / `StaticLayout.getWidth` | 中文标点换行规则不同，长文本用 StaticLayout |
| 文本布局校验 | 新增 JVM 单元测试 | 比对行高、文本包围盒几何，**不做像素 golden**；Pango 与 Android 换行行为允许差异 |
| `cairo.ImageSurface` | `Bitmap.Config.ARGB_8888` + 离屏 `Canvas` | key = (R, night/theme, fonts, density) |
| `random.Random(20261104)` 固定种子星表 | `java.util.Random(20261104)` | **注意**：Java Random 与 Python random 序列不同，需直接固化 STARS/GALAXY 数值表，不要依赖运行时随机 |

### 3.3 静态层缓存

桌面在 `dial.py:_get_static()` 用 ImageSurface 缓存环/字/刻线/四象/晕影，逐秒只画星空与晷针。Android 侧：

- `DialRenderer` 持有 `LruCache<StaticKey, Bitmap>`，key 含尺寸 R、palette 版本、字体族、density。
- **缓存硬约束**：设内存上限阈值；实现简易 Bitmap 对象池复用；`onDetachedFromWindow` / 配置变更时清空全部缓存并主动 `bitmap.recycle()`；星空、晷针等动态元素不进入静态缓存。
- `onSizeChanged` / 配置变更（昼夜锁定、字体切换）时使缓存失效。
- 昼夜渐变期（`0 < blend < 1`）绕过缓存全量重绘，与桌面一致；渐变结束重建缓存。
- **线程拆分**：星空坐标与几何预计算挪至协程子线程；UI 线程 `onDraw` 只执行绘制指令，降低主线程负载（子线程结果须切回主线程更新 View 状态）。
- 仪器测试断言：缓存命中时 `onDraw` 内不重建静态 Bitmap（可注入计数探针）；profiler 校验帧耗时与峰值堆（见 §1.1）。

### 3.4 字体：必须解决可用性与授权

桌面「不打包字体、按名引用系统字体」在 Android 不成立（无楷体/篆体保证）。方案：

1. **内置开源子集字体**：楷体替代用霞鹜文楷（LXGW WenKai，OFL），篆体用霞鹜篆书（LXGW Seal，OFL）。
2. **子集化**：用 `fonttools pyftsubset` 按实际字符集裁剪——12 地支、10 生肖、24 节气、72 候名、干支用字、农历月日、四象与款识用字、数字与 UI 文案。两套字体合计控制在 2 MB 内，满足 12 MB 包体预算。
3. **绝不打包**「字魂游龙篆书」（商用试用，需授权）。
4. `Typeface.createFromAsset` 注册；`TextLayout` 内按 `[seal, kai, serif]` 顺序 fallback，缺字自动降级。
5. 授权文件随 APK 置于 `assets/NOTICE.txt`，设置页提供「许可与署名」入口。

### 3.5 后台保活（v1.1 架构重大修订）

桌面进程常驻即可每秒 tick；Android 不设"永久前台服务 + 每秒 tick"。**秒级驱动按运行态拆分为四条独立路径**：

1. **报时触发（AlarmManager，唯一触发源）**：`setExactAndAllowWhileIdle` 每刻（15 分钟）精确触发；Android 12+ 需 `SCHEDULE_EXACT_ALARM` 权限，无权限时降级 `setAndAllowWhileIdle` + 应用内补偿。时钟回拨/休眠唤醒照搬桌面策略——只评估当前一刻，不补播历史（桌面 `app.py:776–794`）。
2. **报时播放（StrikeService，短暂前台）**：仅在音频播放期间由普通服务转前台，维护 `StrikeQueue` 串行队列（3 声钟/2 声鼓/1 声鼓，200ms 留白）；播完即退出前台、退回普通服务或停止，**不做永久常驻**。
3. **App 主界面（ClockActivity）**：可见时用 `Choreographer` 每秒更新状态并 `invalidate()`；不可见即停止秒循环，避免后台空转。
4. **壁纸（KepanWallpaperService）**：自带 `ClockState` 副本、独立 tick 驱动，完全不依赖主进程服务状态（见 §1.3-4）。

**壁纸功耗三道闸门（写入 P4 DoD）**：

- 可见闸门：`onVisibilityChanged(false)` 立即停止全部绘制循环；
- 空闲闸门：盘面状态无变化时不主动重绘；
- 温控降帧：设备过热自动降低刷新频率（`PowerManager.getThermalHeadroom`，API 31+，低版本跳过）。

**AlarmManager → 前台服务链路的最小探针（P4 开工第一步，未验证不得写死结论）**：

- 待证问题：Android 12+ 从`AlarmManager` 触发的 PendingIntent 启动前台服务播放音频，是否在真机可行（后台启动 FGS 限制 + 精确闹钟权限双重约束）。
- 方法：最小 demo（一个闹钟 + 一个 `mediaPlayback` 型前台服务），在模拟器（API 36）与至少一台真机分别跑，抓 logcat 的 `ForegroundServiceStartNotAllowedException` / 权限拒绝。
- 降级路径：若被拒，改为"闹钟只发通知，用户点击通知后前台播放"；产品文档写明"后台自动报时不可保证"（与 §1.3-5 一致）。
- 探针验证前，本条按**假设**处理，不作为已证实结论。

---

## 4. 工程结构与构建配置

### 4.1 目录（全部位于 `android/`）

```
android/
├── settings.gradle.kts            # rootProject.name + 四模块 include
├── build.gradle.kts               # 只放公共约定，插件版本在各模块声明
├── gradle.properties              # workers=2、Xmx 等（本机 15GB 内存）
├── gradlew / gradlew.bat / gradle/wrapper/
├── local.properties               # 不入库，ANDROID_HOME
├── keystore.properties            # 不入库
├── domain-core/                   # Kotlin JVM library，纯历算，无 Android 依赖
│   └── src/{main,test}/kotlin/cn/workbuddy/kepanclock/domain/
├── render-core/                   # Android library，Canvas 渲染，无 Compose/Activity
│   └── src/main/java/cn/workbuddy/kepanclock/render/
├── wallpaper/                     # Android library，纯原生 Canvas，无 Compose
│   └── src/main/{java/cn/workbuddy/kepanclock/wallpaper,res/xml}/
├── app/                           # Android application，唯一引入 Compose
│   ├── build.gradle.kts           # 版本号唯一来源、ABI splits、门禁、Compose 依赖
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/cn/workbuddy/kepanclock/{app,settings}/
│       │   ├── res/{values,drawable,xml,raw}/   # 字符串/图标/壁纸配置/钟鼓声样
│       │   └── assets/{fonts,NOTICE.txt}/
│       ├── test/                   # JVM 单测 + golden 语料（不进 APK）
│       └── androidTest/            # 仪器冒烟
├── docs/
│   ├── PORTING_PLAN.md            # 本方案（唯一权威看板）
│   ├── archive/                    # 变更输入与立项书归档（diff-v1.0-to-v1.1.md）
│   └── shots/                      # 真机/模拟器验证截图（一次性演示数据）
├── .tmp/                           # 临时探针/夹具，用完即清，不入版本控制
└── dist/                           # release APK 交付目录，只留当前版本
```

Application ID：`cn.workbuddy.kepanclock`（与桌面 `cn.workbuddy.kepan` 区分，避免同机冲突）。

**目录归放纪律见 §10**：开工后所有文件按上表归位，根目录不散落临时文件。

### 4.2 构建基线（本机实测，直接复用，不试错）

| 项 | 值 | 来源 |
|---|---|---|
| AGP | 9.5.1（内置 Kotlin，不再 apply kotlin.android） | scratchpad 实测工程 |
| Gradle | 9.9.1 | gradle-wrapper.properties |
| Compose | **仅 `:app` 引入**，BOM 2025.10.01 + `activity-compose 1.10.1` + material3；`:wallpaper`/`:render-core`/`:domain-core` 零 Compose | §9-3 拍板 |
| compileSdk / targetSdk | 36 | 本机 SDK 仅 android-36 |
| minSdk | 26 | 覆盖主流机型 |
| Java | 17 | 与 scratchpad 一致（实际 JDK 为 JBR 25，向下编译） |
| ABI | arm64-v8a + x86_64，`isUniversalApk=false`，排除 armeabi-v7a | 项目指令 + skill |
| 构建命令 | `ANDROID_HOME=... GRADLE_USER_HOME=... bash gradlew <task>` | skill：`./gradlew` 在受限环境常被拒（126） |

环境变量统一 source `~/Downloads/android/env.sh`，不假设 PATH 已就绪。

### 4.3 产物命名与版本号

- 版本号唯一来源：`app/build.gradle.kts` 顶部 `val appVersionName` / `val appVersionCode`。
- release APK：`kepan-clock-<abi>-release-<versionName>.apk`，用 AGP 9.x `androidComponents.onVariants` 按 ABI 逐个命名（旧 `applicationVariants` 已移除）。
- debug 包不作交付；仅用于体积参考与调试。
- 发版回复附每个 ABI 的**绝对路径** `adb install -r` 命令，交用户自行执行。

---

## 5. 质量门禁（P0 一次性配好）

| 任务 | 内容 | 门槛 |
|---|---|---|
| `testDebugUnitTest` | domain 层自校 + 差分 golden + render 几何/hit_test 单测 | 全通过 |
| `coverageGate` | domain 包（`cn/workbuddy/kepanclock/domain`）行覆盖 | ≥ 80%（目标 90%） |
| `lintRelease` | Lint | 0 error |
| `verifyReleaseBudget` | 每个 release APK 体积 | ≤ 12 MB（含两套子集字体） |
| `stageRelease` | assembleRelease + 包体核验 + 规范命名 → `android/dist/`，**自动清旧版 APK** | 只留本次版本 |
| `connectedDebugAndroidTest` | 仪器冒烟（进绘制、touch、配置写回） | `ANDROID_SERIAL=<设备>` 限定，核对 TEST-*.xml 的 tests/failures |

**JaCoCo 陷阱**：class 目录必须指向 `intermediates/built_in_kotlinc/<variant>/compile<Variant>Kotlin/classes`；指向 `tmp/kotlin-classes/...` 会得到空报告（package.Count=0）。`isIncludeNoLocationClasses=true`，排除 `jdk.internal.*`。

**仪器测试陷阱**：debug 签名与真机 upload key 冲突时会假阳性 BUILD SUCCESSFUL + tests=0；必须核对 XML 计数。SAF 链路本项目不涉及（无文件导入导出）。

---

## 6. 分阶段任务与 DoD

> 每阶段结束固定四件：代码 + 测试 + 门禁全绿数据 + 阶段记录（写回本文 §7）。任何改动轮次都升版本号出包。

### P0 — 工程骨架与门禁（1 轮）

- 建立 `android/` 工程、Gradle wrapper、版本号、ABI splits、签名回退、门禁四件套、`dist/` 交付任务。
- 最小 `ClockActivity` + 空 View 能启动，`stageRelease` 产出可安装空壳包。
- **DoD**：六项门禁中可运行项全绿；空壳 APK 两档真机/模拟器可启动；安装命令记录。

### P1 — 历算内核移植（1–2 轮）

- `ChenCore/SunMath/LunarData/Lunar/ClockState` 全量 Kotlin 化；Python self-test 逐例转 JUnit；生成差分 golden。
- **DoD**：11 例时刻、日出日落/节气/更点/真太阳时、春节 11 例、闰月 5 例、干支锚点、400 日连续性、逐日哈希摘要全过；domain 覆盖 ≥90%。

### P2 — 渲染内核 1:1 迁移（2–3 轮）

- Palette/Geometry/TextLayout/DialRenderer/PopupRenderer/WallRenderer；静态 Bitmap 缓存；昼夜渐变；星空与晷针。
- **DoD**：昼/夜/试时/三日气泡/整月历/设置牌窗/壁画七类场景在真机渲染完整；golden 结构性断言（尺寸、非空、采样点颜色）通过；截图存档 `android/docs/shots/`。

### P3 — 交互、设置与配置（1–2 轮）

- 触摸 hit_test、气泡切换、设置牌窗三选/五选/淡度拖动、DataStore 写回与热重载、试时长按。
- **DoD**：桌面逐项功能对照表全勾；配置写回后半秒内生效；人工验证项在真机确认并记录机型。

### P4 — 报时与壁纸服务（1–2 轮）

- **第一步先做 §3.5 的 AlarmManager → 前台服务最小探针**，结论出来后再定报时实现路径（不可跳过）。
- MediaPlayer 串行队列（3 声钟/2 声鼓/1 声鼓，200ms 留白）；短暂前台 StrikeService + 精确闹钟；WallpaperService 动态壁纸（六秒一换、三道功耗闸门）；静态壁纸快照；BootReceiver。
- **DoD**：真机听感与桌面一致（三声各完整播完不叠）；报时能力在 §1.3-5/6 约束下的表现符合产品文档声明；动态壁纸可见且四角题款完整；壁纸三道闸门生效（不可见停绘、空闲停绘、降帧）；前台服务通知可关闭。

### P5 — 资源合规与发版（1 轮）

- 子集字体与授权文件、音频署名页、敏感信息 grep（序列号/机器名/局域网 IP/绝对路径/凭据）、release 脱敏校验、README 与版本文档。
- **DoD**：全部门禁绿；两档 release APK + 绝对路径安装命令；`docs/shots/` 演示数据截图；本文状态改「已交付」。

---

## 7. 阶段看板（逐轮只更新此处）

| 轮次 | 版本对 | 完成内容 | 门禁数据 | 验证边界 / 遗留 |
|---|---|---|---|---|
| 方案-v1.1 | — | 合入 `diff.md`：profiler 校验、文本布局几何单测、缓存硬约束与线程拆分、秒级 tick 架构四路拆分、壁纸三道闸门、验证边界 4–6 条 | 不适用（文档轮，不出包） | 已按官方资料修正 diff 两处断言：WallpaperService 非独立进程；BOOT_COMPLETED 根因是后台启动 FGS 受限 |
| 方案-v1.2 | — | 用户拍板 §9 五项；文档移入 `docs/`；四模块拆分，Compose 仅 `:app` | 不适用（文档轮，不出包） | 拍板 3 偏离初始推荐，按用户为准 |
| P0-R1 | 1.0.0(1) → 1.0.0(1) | 四模块骨架（domain-core/render-core/wallpaper/app）；Compose 仅 app；ClockView 原生与 ComposeView 平级；六项门禁齐备；最小可运行壳 | `testDebugUnitTest` PASS；domain 行覆盖 **12/12=100%**（门槛 80%）；`lintRelease` 0 error；`verifyReleaseBudget` 两档 0.657/0.658 MB（≤12MB）；`stageRelease` 交付两档；`connectedDebugAndroidTest` 模拟器 tests=1/failures=0/errors=0（XML 核对非假阳性）；三件套：versionCode=1/versionName=1.0.0、v2 签名有效、包体达标 | **验证边界**：真机 `VG6TQSXKJZNNJFUC` 的 adb install 卡在 `Performing Streamed Install`（超时 90s），为设备侧安装链路环境问题，改用模拟器 A/B 对照通过；真机目视确认留待用户。JaCoCo `Unsupported class file major version 69` 为非致命告警（JDK 25），BUILD SUCCESSFUL |
| P1-R1 | 1.1.0(2) | 历算内核全量 Kotlin 化：`ChenCore/SunMath/LunarData/Lunar/ClockState`（domain 共 775 行，对应桌面 751 行纯标准库代码）；golden 差分语料 6 组 4346 行（时刻 1440 / 更点 1440 / 日出日落 96 / 节气 72 / 逐日明文 1096 / 1900–2100 逐年 SHA-256 摘要 201），Python oracle 生成器归档 `docs/archive/gen_golden.py`；桌面自校全部转 JUnit | `testDebugUnitTest` **43 例全过**（含全部 golden 差分逐例/逐摘要一致）；domain 行覆盖 **407/419=97.1%**（门槛 80%，P1 DoD 90% 达标）；`lintRelease` 0 error；包体两档 0.657 MB；`stageRelease` 交付 1.1.0 两档；`connectedDebugAndroidTest` 模拟器 tests=1/failures=0/errors=0（XML 核对）；三件套：versionCode=2/versionName=1.1.0、v2 签名 Verifies、应用名「刻盘钟」 | **验证边界**：golden 比对为数值/文本级（非像素级）；仪器测试仍走模拟器（真机安装链路环境问题未解）；Java 版 `lunarToSolar` 补了显式范围 require（Python 版为数组越界），属有意的移植改进；模拟器用毕已关，`.tmp` 已清 |
| P2-R1 | 1.2.1(4) | 渲染内核 1:1 迁移：`DialData`（星空随机序列固化 479 行）/`Palette`/`RenderGeometry`/`Primitives`（Cairo/Pango→Canvas/Paint/StaticLayout）/`DialRenderer`/`PopupRenderer`/`SceneRenderer`（draw_scene 主入口）；ClockView 接线 SceneRenderer + 秒级 tick；wallpaper 重写接 SceneRenderer；adaptive icon（黑盘+白底圆角方框，anydpi-v26 XML + legacy PNG）；**1.2.0(3) 真机首启崩溃修复**：watermark 丢失桌面 `_ZODIAC_KEY/_FT_KEY` 汉字→表键两级映射致 `table[name]!!` NPE，补映射并顺修 idx/beast 索引 floor 保真（负时区越界），新增 `DialWatermarkTest` 5 例防回归 | `render-core:test` 13 例全过；`testDebugUnitTest` 全过；`coverageGate` 绿（domain 97.1%）；`lintRelease` 0 error；`verifyReleaseBudget` 两档 0.705 MB（≤12MB）；`stageRelease` 交付 1.2.1 两档；**真机链路恢复**：VG6TQSXKJZNNJFUC install Success、启动存活、crash buffer 空、截图目视正常（丑时生肖水印「牛」正确显示）；三件套：versionCode=4/versionName=1.2.1、签名 Verifies、SHA-256 双包记录 | **验证边界**：P2 渲染无 golden 像素差分（星空不可跨语言复现，已固化数据表），保真靠数据表对齐 + 真机目视；1.2.0 崩溃根因是首启渲染路径无仪器覆盖——教训：装机冒烟必须含「启动后 crash buffer 检查」，已纳入本轮流程；`gen_dial_data.py` 归档 `docs/archive/`，`.tmp` 已清 |
| P2-R2 | 1.2.2(5) | 用户拍板竖屏五段式布局（竖屏 h>w 生效，横屏保留桌面构图）：主盘上半屏居中 → 司辰印+读数（左）与更牌窗（右）并列一行 → 下区左块（节气/对时/真太阳时/历注/天行印）+ 右块八刻副盘 → 底部名例「观天之道…」居中；几何集中于 `RenderGeometry.layoutPortrait`（Layout 增 portrait/readCx/gengRect/infoTop/sideCx/sideR/wmTop/nameY 字段），绘制新增 `DialRenderer.panelPortrait`，水印带铺下区背景；`ClockActivity` 的 P0 占位 ComposeView 改默认 GONE（P3 接真设置 UI）；补竖屏结构断言入 `RenderGeometryTest` | `render-core:test` 全过（新增竖屏例）；`testDebugUnitTest` 全过；`coverageGate` 绿；`lintRelease` 0 error；`verifyReleaseBudget` 达标；`stageRelease` 交付 1.2.2 两档；真机 VG6TQSXKJZNNJFUC 安装 Success、versionName=1.2.2、进程存活、crash buffer 空、截图五段布局目视确认 | **验证边界**：真机 USB 本轮间歇掉线（安装与截图分次完成，均成功）；竖屏为用户定制构图、非桌面 1:1，横屏仍为桌面 1:1；下一步 P3 交互/设置/牌窗 |
| P2-R3 | 1.2.3(6) | 用户五项拍板：①盘面文字不清晰修复——根因静态盘位图 drawBitmap 半像素偏移触发双线性采样整盘发糊（绘制坐标取整 + R/位图尺寸取整），并内置子集字体；②读数+更牌改两列并排、整组按实测宽居中于盘底下方 40（layoutPortrait 增 row2Y/gengW/gengH/colGap，横向排布由 panelPortrait 按实测文本宽计算）；③下区（立冬/对时/真太阳时/天行+八刻副盘）下移 28；④历注移至底部名例上方居中（noteRect/anchorX 随之居中）；⑤默认字体改楷体——chen=AR PL UKai CN（Arphic Public License）、seal=霞鹜篆书 LXGW Seal（OFL 1.1），按工程全部用字（902 字符集）pyftsubset 子集化共 493KB 入 assets，ClockView 从 assets 加载并回退 serif；许可文本随包（LXGW OFL 全文待 P5 补） | 测试全过（竖屏结构断言同步更新：R 整数、两列尺寸、历注居中于名例上方）；coverageGate/lint/budget 全绿；包体 1.006 MB（字体后仍远低于 12MB）；三件套 versionCode=6/versionName=1.2.3/签名 Verifies；真机安装 Success、进程存活、crash buffer 空、截图五项逐项目视确认 | **验证边界**：UKai 子集按当前源码字符集生成，新增文案若出集需重新子集化（生成命令记入 P5）；真机 USB 间歇掉线依旧，安装与验证分次完成；下一步 P3 |
| P2-R4 | 1.2.4(7) | 用户示意稿精调 + 盘面模糊根修：①**模糊根因**——ClockView 先 canvas.scale(dpr≈3) 而 静态盘位图按 dp 分辨率(R≈188→位图宽约384px)生成，被画布放大 3 倍贴绘，整盘位图必然发糊（矢量部分清晰佐证）。修复对齐桌面 Cairo 的**设备像素渲染**：drawScene/drawWallpaper 增 dpr 参数，位图按 R×dpr 物理像素生成，恒等矩阵下落点取整 1:1 贴绘（c.setMatrix(null)）；②布局按示意稿（412×867 锚点）：主盘上移段 top=72、上限 h*0.30-top；读数行(司辰印+读数)距盘底 50 居中；更牌窗改读数行下方居中（rowGap=52k，上下两行替代两列）；下区 infoTop=更牌底+56k；leftCx=0.27w、sealY=infoTop+116k、sideCx=0.71w、sideCy=infoTop+80k、sideR=min(76k,0.185w)；历注 noteY=nameY-28k；Layout 字段 colGap→gengY；测试改示意稿锚点断言（容差 5dp） | 测试全过；coverageGate/lint/budget 全绿；包体 1.055 MB；三件套 versionCode=7/versionName=1.2.4/签名 Verifies（SHA-256 d25530…79d46）；真机 debug+release 双验证：进程存活、crash buffer 空、截图盘面文字锐利、布局与示意稿一致 | **验证边界**：真机 USB 链路本轮持续 EOF 掉线（传输即断），已切 adb-over-WiFi（tcpip 5555 + connect <adb-over-WiFi>）完成全部安装/截图；USB autosuspend 已关仍不稳，疑线缆/接口硬件问题，建议用户换线；下一步 P3 |
| P3-R1 | 1.3.0(8) | P3 交互/设置/配置全链：①触摸命中分发——seal（天行印开合 Compose 设置页）/ note（三日气泡）/ footer（展全月↔收）/ popup（保持）/ 点盘面收气泡与设置页；②试时长按：长按激活→按住移动实时更新→松手保留读数→单击清除（render 层 hoverRead 1:1 桌面 hover 块，JVM 单测锁午正/子正/酉右/卯左四锚点）；③Compose 设置牌窗（§9-3 拍板，FrameLayout 底部浮层与 ClockView 平级）：昼夜三选/剪影五选/淡度滑轨/辰字楷明/观测点（纬度经度昏旦偏移，输入校验后写回），改即存 DataStore 即刻生效（真机「常昼」秒切昼盘实证）；④ConfigRepository（DataStore Preferences，键名对齐桌面 mode.theme/panel.watermark/panel.watermark_alpha/fonts.chen/place.*/dusk.offset_minutes）；⑤渲染层：SceneRenderer 指针生效态（hover/noteHover/sealHover）、StaticKey 加字体签名（楷明切换缓存失效）、radialBackdrop lint 根修（API31 双心 RadialGradient 补 minSdk26 单心等效回退）；⑥真机实证两缺陷修复：a) GestureDetector.onTouchEvent(DOWN) 不承诺 true，透传 super 致视图不锁定触摸目标、UP 永不再派发——onTouchEvent 显式接管触摸流；b) 竖屏气泡底栏与历注命中框重叠、桌面「先 note 后 popup」顺序吞掉 footer——改为先气泡后历注＋回归单测 | domain 43 例＋render 16 例＋app 4 例（ConfigLogic 钳制/白名单/坐标校验）全过；coverageGate 97.1%；lintRelease 0 error；包体两档 1.468 MB；三件套 versionCode=8/1.3.0/签名 Verifies；真机（Android 12，720×1600，dpr 1.8）adb-over-WiFi：安装 Success、进程存活、crash buffer 0 条、常昼↔随昼夜热重载、三日气泡（七政曜名/今日朱框）、整月历（立冬/朔日朱标）、试时「午正一刻」逐项目视确认，截图 docs/shots/kepan-1.3.0-* | **验证边界**：①试时与气泡的注入坐标体系为 display=dp×1.8＋状态栏 64px，仅供探针，人工触感待用户实机确认；②淡度滑轨拖动手感、飞天/神兽/辰字/无水印逐项切换为人工验证项；③辰字「明体」以系统 serif 承接（工程未内置明体，字形随设备字库）；④壁纸动作（壁画自启/静态壁纸）属 P4，本版设置页不出现；⑤lint 曾报 render-core NewApi error 为历史存量（P2 轮门禁未在库级 lint 拦截），本轮根修。下一步 P4 报时与壁纸（开工第一步 AlarmManager→FGS 最小探针不可跳过） |
| P4-R1 | 1.4.0(9) | P4 报时链路全交付＋设置牌窗风格重做：①**AlarmManager→前台服务最小探针（§3.5 第一步，未跳过）**：SCHEDULE_EXACT_ALARM + setExactAndAllowWhileIdle（canScheduleExactAlarms()==false 时降级 setAndAllowWhileIdle）→ AlarmReceiver 只评估当前一刻（无补播，对齐桌面 app.py:778–793）→ startForegroundService；真机 Android 12 实证：40 s 试钟探针（21:26:27）与 21:30:00 自然刻线两轮触发均成功，**无 ForegroundServiceStartNotAllowedException**，播完 stopSelf（短暂前台，dumpsys 服务记录归零）；②StrikeService：mediaPlayback 前台 + AudioTrack MODE_STREAM 阻塞写完即播完；串行队列不叠发（上一声播完留 200 ms 再起，对齐桌面 _StrikeQueue/_STRIKE_GAP）；③声样运行时合成（StrikeAudio）：22050 Hz 单声道逐值对齐桌面 _synth_strike（bell 0.45 s 三分音 / drum 0.4 s 扫频 150→62 Hz），峰值归一 ×31000，无资产入包；④BootReceiver 开机重布 + Activity 启动重布（开启态）；FGS 被拒降级通知（§3.5 降级路径，代码在位——本轮真机未触发）；⑤设置牌窗风格重做（用户需求）：底/墨/描边/朱全部取 Palette 昼夜两套（night 参数跟随 ClockView.effectiveNight，blend≥0.5 视夜），自绘 chip（朱框选中/描边未选）替代 Material 默认配色，滑轨/输入框朱-描边配色，题款改「天行健，君子以自强不息」楷体朱字，新增「报时」开关（sound.enabled，对齐桌面 sound.enabled 默认关）；⑥报时开 → 布下一刻闹钟 + 40 s 试钟探针；关 → 撤全部闹钟 | domain 43＋render 16＋app 12 例（新增 StrikeLogic/StrikeAudio 8 例：触发式锚点 时辰初钟三/时辰正鼓二/每刻鼓一/非刻线不报/刻边界/事件序列/合成形状/串行时长）全过；coverageGate 97.1%；lintRelease 0 error；包体两档 1.452 MB；三件套 versionCode=9/1.4.0/签名 Verifies；真机（Android 12）：安装 Success、crash buffer 0 条、设置牌窗昼/夜两态目视确认（米白底墨字/暗底金字均随主题热重载）、报时开关改即存、试钟+自然刻线两轮触发、截图 docs/shots/kepan-1.4.0-* | **验证边界**：①听感（钟鼓声是否入耳、音量）为人工验证项，logcat/通知证据只证明播放链路走通；②探针未覆盖：Android 14+ 默认拒发 SCHEDULE_EXACT_ALARM 的机器与 FGS 被拒真机（降级通知路径代码在位未实证）；③设置牌窗昼夜跟随在 blend 过 0.5 时需下次重组刷新（cfg 变更即刷）；④壁纸（壁画构图/三道功耗闸门/静态快照）属 P4-R2 下一轮；⑤版本号自本轮起按轮递增（1.4.0(9)） |\n
| P4-R2 | 1.5.0(11) | 四项用户反馈全闭环＋P4 壁纸全量：①**题款字体统一**——fontTools 查明 UKai 子集 898 字缺「健/君/息」（回退系统黑体致黑楷并存），从 /usr/share/fonts/truetype/arphic/ukai.ttc（font-number=0）重生成 901 字子集（439KB）入 assets，真机题款全楷目视确认；②**黑/白主题扩展通知栏**——ClockActivity.applySystemBars：statusBarColor=Palette.bg＋WindowCompat InsetsController isAppearanceLightStatusBars=!night，昼米白底深图标/夜暗底浅图标（像素采样 247,244,234=Palette day bg 实证）；报时前台通知与降级通知 accent 朱色随生效昼夜（service 内 runBlocking+ClockState.compute 实算 blend）；③**21:30 无声根修**——dumpsys audio player 事件实证：1.4.0 播放 started→stopped 仅 222ms（0.4s 鼓样被截断）；根因 AudioTrack 缓冲恰容整段 PCM、write() 瞬回不代表播完，随后 stop() 丢弃缓冲未播音频；改为轮询 playbackHeadPosition 至帧尾再 pause/flush/stop；复测 492ms 完整播完；④**壁纸全量（P4-R2d）**——KepanWallpaperService 真实现替换 P2 空壳：SceneRenderer.drawWallpaper 壁画构图 1:1 桌面（大盘 0.36×短边居中/星宿/针/四角竖题/底部读数行，真机快照四角题款完整）；自带 ClockState 独立 tick（平稳 6s/渐变 100ms，对齐桌面 TICK_*）；三道功耗闸门：onVisibilityChanged(false) 立停、盘面签名（tMin/blend/geng/read/配置/尺寸）未变跳绘、API31+ thermalStatus≥MODERATE 降至 30s；配置流 collect 热重载（昼夜/辰字/观测点）；⑤设置牌窗新增「壁纸」行：动态壁画（ACTION_CHANGE_LIVE_WALLPAPER 预选本服务）/静态快照（drawWallpaper 渲染整屏→MediaStore Pictures，真机导出 kepan-wall-20261121-215116.png 实证）；⑥架构微调：settings 包（KepanConfig/ConfigRepository）自 :app 迁入 :render-core（包名不变），:wallpaper 依赖 render-core 共享同一 DataStore 实例（同进程单例，避免双实例同文件崩溃） | 71 例全过（domain 43＋render 16＋app 12）；coverageGate 97.1%；lintRelease 0 error；包体两档 1.474 MB；三件套 versionCode=11/1.5.0/签名 Verifies；真机：安装 Success、crash buffer 0、题款全楷、状态栏昼米白（像素采样）/夜暗两态、快照落盘 Pictures、试钟播放 492ms 完整，截图 docs/shots/kepan-1.5.0-* | **验证边界**：①听感最终确认人工项——鼓基频 150→62 Hz 手机小扬声器物理受限，若复测仍偏弱可议音高适配（偏离桌面逐值对齐需拍板）；②动态壁纸需在系统壁纸选择器人工设置后长时观察功耗闸门；③statusBarColor 于 Android 15+ 弃用（edge-to-edge 强制），本机 Android 12 正常；④settings 迁移偏离 §4.1 目录（记录在案）；⑤uiautomator dump 取 chip 精确坐标替代缩略图估算（本轮第二次踩缩略图比例坑后确立） |
| P4-R3 | 1.6.0(12) | 三项用户反馈全闭环：①**壁纸按手工示意稿精调＋字体统一**——竖屏构图重写（横屏保留桌面四角构图）：顶节气名篆书二字（st.term，候名六字归底部竖排）＋左右干支竖题＋中央淡衬方朱框「天行」楷体印（0.25w/0.145w，与干支列避让）；大盘（外金环＋辰字环 1.115R 朱楷＋九十六刻金线环＋内盘微亮底＋星空＋四象章＋盘心司辰红篆＋金针）；底部刻读/更点/干支日三行居中＋竖排候名六字（0.822→0.985h 自适应缩字）。全部文字取包内 UKai/LXGW 子集零系统回退（lunar.py 全语料缺字 0 实证）；瓦当章 12.3dp<14dp 回退篆字为桌面同款小盘行为（dp 空间阈值）。两轮真机迭代：v1 候名误置顶部（6 篆字过宽）与 st.note 完整历注串误竖排（含日期与间隔点）→ 改 st.term + parts.hou 后与示意稿对齐；②**「钟鼓声被系统预设铃声取代」根修**——dumpsys notification 铁证：1.4.0 首建的 strike 渠道 mImportance=3(DEFAULT)+mSound=系统预设通知铃声（渠道属性一经创建不可变），每次报时前台服务先响系统铃声盖过声样；修复：删除旧渠道＋新建 strike_fg（IMPORTANCE_NONE+setSound(null)），设备实证 mSound=null（FGS 最低显示提升 LOW 但无声）；③**手机端音高适配（用户拍板，偏离桌面记录在案）**——drum 桌面 150→62 Hz 手机微型扬声器物理不可闻，适配 320→130 Hz 扫频＋2.4 倍频皮膜泛音（0.30 幅度），时长 0.4 s 不变；bell 660/1320/1980 Hz 小扬声器可闻维持桌面逐值对齐；比值断言单测锁公式（归一化在比值中约去，容差 1e-3 容 Short 量化）| 72 例全过（新增 drum 适配锁定 1 例）；coverageGate 97.1%；lintRelease 0 error；包体两档 1.474 MB；三件套 versionCode=12/1.6.0/签名 Verifies；真机：22:30:00 自然刻线与 22:30:50 试钟探针两轮触发，bell 450 ms 完整播放，窗口内零系统铃声 player 事件（渠道静音实证），crash buffer 0；快照三轮迭代 docs/shots/kepan-1.6.0-wallpaper-*.png| **验证边界**：①听感最终确认人工项（drum 适配后 320→130 Hz 主频是否入耳请实机点验，仍偏弱可再上移）；②示意稿顶部「企晏」二字解读为节气名（篆书）——若实为其他语义（如候名简称）请指正；③壁纸需在系统壁纸选择器人工设置后长时观察；④P5 合规发版收尾 |
| P5-R1 | 1.6.1(13) | P5 合规发版收口：①**天行印出框根修**（用户截图实证：两字横向撑破朱框）——根因竖屏 h>w，字号取 h 比例（0.052h≈166px 两字宽）而框取 w 比例（0.145w≈104px），必然出框；修复为 Paint 实测文本宽反算字号（字宽 ≤ fs×0.76、字高 ≤ fs×0.52），提取纯函数 RenderGeometry.sealTextSize＋回归单测锁三态（竖屏收敛/横屏保留基准/非正宽退回基准）；真机快照印字入框居中实证；②**LXGW OFL 1.1 全文补齐**——assets/fonts/LICENSE-lxgw-seal-OFL.txt（版权行取自字体 name 表 nameID=0，Reserved Font Name 霞鹜/霞鶩/落霞孤鹜/落霞孤鶩/LXGW），随包入 APK 实证；③**敏感信息扫描双通道**——源码 grep（kt/kts/xml/pro，密钥/令牌/内网 IP/序列号/个人路径 0 命中，build 报告命中不入包）＋终版 APK 逐文件字节扫描（dex/arsc/ttf/png/xml 对个人绝对路径、内网 IP 段、设备序列号/型号 0 命中）；④**README 与版本文档**——android/README.md：功能/构建门禁/版本表 1.0.0→1.6.1/第三方许可表（UKai Arphic＋LXGW OFL＋androidx Apache-2.0，均随包）/音频署名（运行时合成无第三方素材）/隐私声明（不联网不采集）；⑤**演示截图**——docs/shots/ 20 张全版本过程图＋1.6.1 终版盘面/壁纸快照| 73 例全过（新增 sealTextSize 1 例）；coverageGate 97.1%；lintRelease 0 error；包体两档 1.476 MB（OFL 全文 +2KB）；三件套 versionCode=13/1.6.1/签名 Verifies；SHA-256：arm64 c5ac01004c22977d…／x86_64 41ba70d1368fc1c7…；真机：安装 Success、versionName=1.6.1、进程存活、crash buffer 0| **验证边界**：①壁纸/听感/触感等人工点验项沿用 P4 各轮记录；②repo 文档（PORTING_PLAN.md）原含真机 IP 与序列号，2026-11-22 GitHub 发布前清洗轮已全部泛化（`<adb-over-WiFi>`/「真机」/相对路径）；③Android 15+ edge-to-edge 对 statusBarColor 的弃用影响留待后续系统适配轮 |
| P5-R2 | 1.6.2(14) | 三项壁纸精调（用户反馈）：①**瓦当章改神兽线描图案**——竖屏壁纸不再走桌面 MEDALLION_MIN 小盘篆字回退（12.3dp<14dp），直接 Primitives.beast 四象线描（scale 0.62→0.70 提升小尺寸可辨度，线宽 mr×0.10）；真机快照朱雀线描图案实证；②**底部读数三行统一**——刻读/更点/干支日三行由 0.030/0.0255/0.0215h 三种字号与不齐行距统一为 0.024h 字号＋0.044h 行距，自 0.700h 整体下移至 0.716h（三行 0.716/0.760/0.804h，底行字底 0.818h 仍避开竖排候名 0.822h）；真机快照三行齐整实证；③**天行印改篆书＋底色同壁纸**——印文与盘心「司辰」篆款改用**全字庫說文解字篆體**（TWSeal 4 字子集，4KB，ebas927.ttf 下载自 cns11643.gov.tw，政府資料開放授權條款-第1版 可商用可再分发，用户拍板选型）：霞鹜篆书为 165 字早期 alpha 缺 行/司，華瑞小篆授权不可核，字魂游龙商用需授权均不可入包；Primitives.Fonts 增 sealAlt 字段（默认=seal 保持兼容），印文/司辰（盘心＋朱印 chip 三处）换 sealAlt，StaticKey 字体签名加入 sealAlt 防缓存残留；印章衬方 hair@0.15 改 pal["bg"] 实填（遮星且与壁纸底色一致，色差消除）；④主盘其余篆书语境（节气名/生肖/弹窗题款/瓦当回退）保持霞鹜（全字庫缺 雪/龙/苍/设/历 等简体字，不能整体替换）；⑤LXGW 子集曾补 行/司 未果（源字体无此二字字形），回退方案为 sealAlt 双字体；许可文件 LICENSE-tw-cns-seal.txt 随包，README 许可表更新 | 73 例全过；coverageGate 97.1%；lintRelease 0 error；包体两档 1.482 MB（+TWSeal 4KB）；三件套 versionCode=14/1.6.2/签名 Verifies；真机：安装 Success、versionName=1.6.2、进程存活、crash buffer 0；快照三项目视确认：印文篆书圆转入框、衬底无色差、瓦当线描兽形、底部三行字号行距齐整下移，docs/shots/kepan-1.6.2-* | **验证边界**：①天行印文两字跨 LXGW/TWSeal 两种篆书体系仅存在于不同元素间（印文整串同字体），盘内无单字混排；②TWSeal 子集仅 4 字，篆书语境新增文案若超出霞鹜覆盖需扩充子集；③說文解字字型授权为政府开放授权条款，商用分发需保留来源标注（已随包） |
| P5-R3 | 1.6.3(15) | 用户六项反馈全闭环：①**壁纸干支题篆书化＋补全＋对称**——竖屏顶题组左右干支竖题改華瑞小篆金字并补全为「丙午年」（右列）/「己亥日」（左列），沿竖中线对称并列（±0.085w，align l/r 对称排布，改画于衬方之后不被衬方遮盖）；②**节气名竖写居中**——顶部「立冬」由横排改 vtext 竖写（0.050h 起、0.032h 字号、居中）；③**表盘外辰字环改篆书**——壁纸大盘外十二辰字（1.115R）由楷体改篆书；④**篆书主字体换華瑞小篆**——用户拍板（北师大已无人维护、本 app 非商用），取 FreeMdict topic/45318 作者发布版 4.2（Google Drive 备用源 16.9MB），GB2312 全汉字＋ASCII 子集化 6858 码位（3.82MB）替换 LXGWSeal/TWSeal（两文件与许可删除），sealAlt 与 seal 同源保留兼容位；许可说明 LICENSE-huarui-seal.txt 随包；⑤**字体切换加篆书且全局生效**——KepanConfig.chenKai:Boolean 重构为 font:String（kai/ming/zhuan 三选，白名单归一，DataStore 键 fonts.chen 兼容旧值），设置牌窗「辰字」行改「字体」三 chip（楷体/明体/篆书），ClockView/wallpaper 字体表 Map<String,Fonts>，zhuan 态 chen 位换篆书使全部正文篆化（真机实证整盘篆化），SceneState.chenFontKai→fontFace（遗留画布设置牌窗同步）；⑥**报时「电子音」根修**——桌面 `_ensure_sounds` 三级来源实证：实际播随包实录 bell.wav/drum.wav（22050Hz 单声道，bell 3.0s/drum 1.5s），合成仅兜底；Android 端 assets/sounds 随包同源 WAV＋StrikeAssets 纯 Kotlin WAV 解析（fmt/data 块遍历），StrikeService 优先实录、缺失回退合成；StrikeAudio 增 BELL_REAL_SEC/DRUM_REAL_SEC，sequenceDurationSec 按实录时长 | 单测全过（30 例 debug 变体；ConfigLogic 增字体归一例、StrikeLogic 串行时长改实录锚点 9.40/3.20/1.50s）；编译三模块绿；真机：安装 Success、crash buffer 0；设置牌窗字体三选实证（篆书选中态截图）；全局篆化目视确认（辰字环/读数/更牌/历注/名例全篆）；静态快照四项目视确认：立冬竖写居中、丙午年/己亥日篆书对称并列、辰字环篆书、天行印正常无重叠（docs/shots kepan-wall-20261122-000359.png）；包体两档 4.17MB（+3.8MB 字体子集，远低于 12MB 闸门） | **验证边界**：①实录声样真机已复核——00:15 自然刻线（drum1）dumpsys audio 实证 AudioTrack 播放 00:15:00.048→00:15:01.614 ≈ **1.57 s**，实录 drum.wav 时长（旧合成音为 0.4 s），根修确认；②華瑞小篆未获北师大授权（作者声明无法联络、疑似无人维护），本 app 非商用引用，商用分发需重新评估；③源字体无 ASCII 数字/冒号，全局篆书态下时间数字由系统回退渲染（与桌面 Pango 行为一致）；④明体仍以系统 serif 承接 |
| P5-R4 | 1.6.4(16) | 用户两项微调：①**壁纸「天行」印与「立冬」位置互换**——天行印整组（衬方＋朱框＋篆书印文，中心 0.091h 取原节气名竖排块几何中心）移至顶部，节气名移至中央 0.252h 并按字数垂直居中（ty=scy−n×step/2）；干支竖题「丙午年/己亥日」保持 0.180h 居印章与大盘之间；②**设置牌窗题款篆书化＋居中**——「天行健，君子以自强不息」由楷体改華瑞小篆（HuaRuiSeal.ttf，GB2312 子集已含全部题款字），textAlign=Center＋fillMaxWidth | 单测/lint 绿；真机：安装 Success、crash 0；静态快照目视确认（顶部朱框天行印、立冬竖写居中、干支题无重叠）；设置牌窗题款篆书居中目视确认；包体两档 4.17MB | **验证边界**：①壁纸/听感人工点验项沿用各轮记录；②華瑞小篆非商用引用边界沿用 P5-R3 |
| P5-R5 | — | GitHub 发布轮（无代码变更）：①**同库重组提交**——桌面版移入 src/docs/packaging/previews/screenshots/assets 子目录（git rename 保留历史），android/ 全量入库（.gitignore 补 .gradle//tmp//.workbuddy/，build/dist/local.properties 已排除）；②**文档清洗**——PORTING_PLAN/archive/RELEASE-v1.5.3 的真机地址→`<adb-over-WiFi>`、序列号→「真机」、绝对路径→相对/家目录，全库 grep 复查 0 残留；③**根 README**——双平台总览＋desktop-v*/android-v* 下载指引；④**tag 改写**——8 个桌面 tag v1.4.0–v1.5.3 重命名为 desktop-v*（annotated，指向原 commit，资产先备份后重传、sha256 逐一校验一致，release 正文同步清洗，desktop-v1.5.3 为 Latest）；⑤**android-v1.6.4 Release 首发**——双 APK（4.17MB×2）＋中文发布说明＋sha256 校验表，标记 Latest | main 已推送（270afa3）；9 个 release 全部在线可下载 | **注意**：旧版 v* 下载 URL 因 tag 改写而失效（用户拍板彻底改写）；后续发布按 desktop-v* / android-v* 平台前缀 |
---

## 8. 许可与合规（硬约束）

| 资产 | 许可 | 要求 |
|---|---|---|
| 钟声 `bell.wav` | CC0 | 无署名义务，保留来源说明即可 |
| 鼓声 `drum.wav` | CC BY-SA 3.0（Clngre） | APK 内 `NOTICE.txt` 署名 + 协议链接；修改声样则派生部分同协议 |
| 霞鹜文楷 / 霞鹜篆书 | OFL 1.1 | 随包附 OFL 全文；子集化后仍需声明保留原许可 |
| 字魂游龙篆书 | 商用试用 | **禁止打包**；仅桌面端按名引用，Android 端一律 OFL 字体 |

---

## 9. 决策项（已拍板 2026-11-21）

### 9.1 拍板记录（用户原文编号）

| 项 | 拍板 | 落地方式 |
|---|---|---|
| 1 渲染内核 | **1-1** 原生 Canvas 1:1 | 四模块中 `:render-core` 用 `android.graphics.Canvas`，不引 Compose/WebView |
| 2 版本线 | **2-1** Android 独立从 1.0.0 起 | `appVersionName="1.0.0"`, `appVersionCode=1`，与桌面 1.5.3 无关 |
| 3 UI 框架 | **3-设置页 Compose**：Activity 内 ComposeView 与原生 ClockView **平级**嵌入；盘面保留原生自定义 View，**严禁把盘面放进 AndroidView**；wallpaper 模块纯原生 Canvas；**仅 app 模块引入 Compose 依赖** | 四模块结构保证依赖隔离；ClockView 是 Activity 内容视图的直接子 View，ComposeView 兄弟节点 |
| 4 定位方式 | **4-1** v1 沿用手动经纬度配置 | 配置沿用 `[place] latitude/longitude` 键名，不申请定位权限 |
| 5 产品定位 | **5-1** 自用/作品展示 | 按全量功能做，不准备分发素材与隐私政策（若日后分发另立轮次） |

> 拍板 3 与初始推荐（纯 View 零 Compose）不同，以用户拍板为准；因此产生四模块拆分与 `:app` 单独依赖 Compose 的结构调整。

---

## 10. 目录归放纪律（开工后强制）

**约定**：开工后 `android/` 目录保持整洁，所有文件按 §4.1 目录树归位，根目录不留散落文件。

1. **固定归位**：
   - 方案与看板只在 `PORTING_PLAN.md`，禁止另建"方案副本/阶段报告"造成多头权威；
   - 变更输入、立项书、旧版 diff 一律进 `docs/archive/`，文件名带版本对（如 `diff-v1.0-to-v1.1.md`）；
   - 验证截图进 `docs/shots/`，只用一次性演示数据，禁止含真实设备序列号/局域网 IP；
   - 临时探针、调试日志、adb 导出、夹具进 `.tmp/`，用完即清，不入版本控制。
2. **构建产物**：`app/build/` 里程碑前清理；release 交付只进 `dist/`，`stageRelease` 自动清旧版，目录内始终只有当前版本的两档 APK。
3. **签名与本地配置**：`local.properties`、`keystore.properties`、`*.jks` 永不入库，根目录保留但由根 `.gitignore` 排除。
4. **测试资产隔离**：golden 语料只在 `app/src/test/resources/`，不进 `src/main/`，由打包脱敏校验扫描确认。
5. **每轮收尾检查**：lint → 单测 → 门禁 → 截图归档后，核对 `android/` 根目录文件清单与 §4.1 一致，多的移入对应子目录或清除；核对结果记入 §7 看板该轮行。
6. **禁止事项**：不得在 `android/` 根目录直接写 `.log`、`.tmp`、`*.png`、临时 `.kt`/`.gradle` 探针文件；不得在桌面侧 `src/`、`assets/` 落任何 Android 相关文件（只读引用）。
