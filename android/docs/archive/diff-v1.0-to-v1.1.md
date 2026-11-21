--- PORTING_PLAN.md (v1.0)
+++ PORTING_PLAN.md (v1.1 改良版 · 待拍板)
@@ -1,6 +1,6 @@
 # 刻盘钟 → Android 完整移植方案
-> 版本：v1.0（2026-11-20） · 状态：待拍板 · 本文是唯一权威看板，其余文档只留版本戳并指向本文。
+> 版本：v1.1（2026-11-20） · 状态：待拍板 · 本文是唯一权威看板，其余文档只留版本戳并指向本文。
 > 工程根：`android/`（所有 Android 项目文件集中于此，桌面侧 `src/`、`assets/` 只读引用，不改动）。
 ---
 ## 0. 结论先行
@@ -26,7 +26,8 @@
 | 壁画模式（全屏置底、六秒一换、四角竖题） | WallpaperService.Engine（动态壁纸）+ 静态壁纸快照 |
 | 开机自启壁画 | RECEIVE_BOOT_COMPLETED + 前台服务；壁纸由系统托管无需自启 |
 | 配置热重载 | DataStore Flow collect，改即生效 |
-| `--png` 离屏自检 | JVM 单测 + 仪器测试渲染为 Bitmap，golden 结构性断言 |
+| `--png` 离屏自检 | JVM 单测 + 仪器测试渲染为 Bitmap，golden 结构性断言；补充内存&性能 profiler 校验 |
 
 ### 1.2 明确不移植
 - GTK4 / PyGObject / Cairo / PangoCairo / tomllib / paplay / gsettings / .desktop 自启项（平台专属，全部替换）。
@@ -44,6 +45,11 @@
 2. **交互落点无法无人值守**：Wayland 宿主上模拟器指针注入不可靠；设置牌窗拖动、气泡点击等请用户实机确认，文档标注为「人工验证项」。
 3. **后台保活受 OEM 限制**：报时与壁纸在部分国产 ROM 上可能被冻结，需在真机验证并记录机型差异。
+4. WallpaperService 为独立系统进程，**严禁依赖 App 主进程服务状态**；壁纸模块必须自带完整 ClockState 副本与 tick 驱动逻辑。
+5. Android13+ FGS 任务管理器允许用户手动终止前台服务；报时无法做到100%后台可靠，产品文档必须写明该约束。
+6. `RECEIVE_BOOT_COMPLETED` 静态广播限制收紧：开机不会自动恢复报时；必须由用户手动打开一次 App，报时能力才恢复。
 
 ## 2. 架构分层与文件映射
 ### 2.1 分层
@@ -92,7 +98,7 @@
 | `app.py`（UI/声音段） | ~700 | app/* | 事件控制器→触摸监听；GLib.timeout→Handler/coroutine；paplay→MediaPlayer | 中高 |
 
 ### 2.3 差分测试（保证历算不走样）
-以 Python 为 oracle，生成固定语料后由 Kotlin 逐例比对：
+以 Python 为 oracle，生成固定语料后由 Kotlin 逐例比对；**文本布局几何新增单测，不做像素比对，比对布局边界/行高**：
 1. `chen_core`：0–1439 分钟逐分钟输出 `read_str/needle_angle/animal` → 1440 行 golden。
 2. `sun`：1900–2100 每年抽样 4 个节气日 + 冬至/夏至，输出日出日落/更点/真太阳时。
 3. `lunar`：1900-01-31 至 2099-12-31 逐日 `solar_to_lunar/gz_day/hou` 的哈希摘要（Python 侧算一次 SHA-256，Kotlin 侧重算比对），避免提交 7 万行文件。
@@ -112,6 +118,13 @@
 | `set_source_surface + paint` | `drawBitmap` | 静态层缓存见 3.3 |
 | `PangoCairo.create_layout / set_font_description` | `TextPaint` + `StaticLayout` | 字体链需自己实现 fallback（见 3.4） |
 | `layout.get_width` | `paint.measureText` / `StaticLayout.getWidth` | 中文标点换行规则不同，长文本用 StaticLayout |
+| 文本布局校验 | 新增 JVM 单元测试 | 比对行高、文本包围盒几何，不做像素 golden；Pango 与 Android 换行行为允许差异 |
 | `cairo.ImageSurface` | `Bitmap.Config.ARGB_8888` + 离屏 `Canvas` | key = (R, night/theme, fonts, density) |
 | `random.Random(20261104)` 固定种子星表 | `java.util.Random(20261104)` | **注意**：Java Random 与 Python random 序列不同，需直接固化 STARS/GALAXY 数值表，不要依赖运行时随机 |
 
@@ -119,11 +132,26 @@
 桌面在 `dial.py:_get_static()` 用 ImageSurface 缓存环/字/刻线/四象/晕影，逐秒只画星空与晷针。Android 侧：
 - `DialRenderer` 持有 `LruCache<StaticKey, Bitmap>`，key 含尺寸 R、palette 版本、字体族、density。
+- **缓存硬约束改良**：设置内存上限阈值；实现简易 Bitmap 对象池复用位图内存；onDetachedFromWindow / 配置变更时清空全部缓存，主动调用 `bitmap.recycle()`；星空、晷针动态元素不进入静态缓存。
 - `onSizeChanged` / 配置变更（昼夜锁定、字体切换）时使缓存失效。
 - 昼夜渐变期（`0 < blend < 1`）绕过缓存全量重绘，与桌面一致；渐变结束重建缓存。
 - 仪器测试断言：缓存命中时 `onDraw` 内不重建静态 Bitmap（可注入计数探针）。
+- **线程拆分**：星空坐标、几何预计算挪至协程子线程；UI线程 `onDraw` 仅执行绘制指令，降低UI线程负载。
 
 ### 3.4 字体：必须解决可用性与授权
 桌面「不打包字体、按名引用系统字体」在 Android 不成立（无楷体/篆体保证）。方案：
 1. **内置开源子集字体**：楷体替代用霞鹜文楷（LXGW WenKai，OFL），篆体用霞鹜篆书（LXGW Seal，OFL）。
 2. **子集化**：用 `fonttools pyftsubset` 按实际字符集裁剪——12 地支、10 生肖、24 节气、72 候名、干支用字、农历月日、四象与款识用字、数字与 UI 文案。两套字体合计控制在 2 MB 内，满足 12 MB 包体预算。
@@ -137,21 +165,45 @@
 
 ### 3.5 后台保活（新增，桌面无对应物）
 桌面进程常驻即可每秒 tick；Android 必须显式设计：
-- `TickService`：前台服务 + 常驻通知（低优先级、可关闭通知提示），持有 `ClockState` 更新与 `StrikeQueue`。
-- `AlarmManager.setExactAndAllowWhileIdle`：每刻（15 分钟）精确触发报时；Android 12+ 需 `SCHEDULE_EXACT_ALARM` 权限，降级路径用 `setAndAllowWhileIdle` + 应用内补偿。
-- 屏幕关闭时用 `Choreographer` 停止逐帧、仅保留 Alarm；亮屏恢复重算。
-- 时钟回拨/休眠唤醒：照搬桌面策略——只评估当前一刻，不补播历史（见桌面 `app.py:776–794`）。
+>【架构重大修订】TickService 不再承担**每秒秒级tick循环**；秒级驱动拆分归属：
+1. **AlarmManager 精确闹钟**：唯一负责报时事件触发；Android12+ `SCHEDULE_EXACT_ALARM`，提供降级路径；时钟回拨/休眠唤醒：只评估当前一刻，不补播历史。
+2. `TickService`：**仅音频播放期间短暂切换为前台服务**；报时音频播放完毕即可退回普通服务，不做永久常驻前台。维护 `StrikeQueue` 声音串行播放队列。
+3. **App 主界面 ClockActivity 可见状态**：Activity 内部通过 `Choreographer` 做每秒状态更新 + `invalidate()`；Activity 不可见停止秒循环。
+4. **KepanWallpaperService（独立系统进程）**：内部自带独立 `ClockState` 副本、独立 tick 驱动；**完全不依赖 App 主进程 TickService**。
+    - 功耗三道闸门必须实现，写入DoD：
+      - 可见闸门：`onVisibilityChanged(false)` 立刻停止所有绘制循环；
+      - 空闲闸门：盘面无状态变化，停止主动重绘；
+      - 温控降帧：设备过热自动降低刷新频率。
+5. `RECEIVE_BOOT_COMPLETED`：仅做可选恢复尝试；产品文档明确约束：开机后用户必须手动打开一次App，报时方可恢复。
 
 ## 4. 工程结构与构建配置
 ### 4.1 目录（全部位于 `android/`）
