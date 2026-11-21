# 刻盘钟 · 司辰（Android）

十二时辰盘钟的 Android 移植版。桌面版见仓库根 `docs/README.md`。

## 功能

- 十二时辰盘钟：星宿、晷针、九十六刻、更点制，随昼夜自动切换（可锁定昼/夜）
- 历注气泡：昨/今/明三日条、整月历（七政曜日、朔日/节气朱标）
- 试时长按：按住盘面任意角位实时读数
- 设置牌窗（改即存）：昼夜三选、剪影五选、淡度、字体三选（楷/明/篆，全局生效）、观测点、报时开关、壁纸动作
- 报时：时辰初钟三、时辰正鼓二、每刻鼓一；随包实录声样（桌面同源 WAV），合成兜底
- 动态壁纸 / 静态快照：壁画构图，三道功耗闸门（不可见停绘、状态未变跳绘、过热降帧）

## 构建

```bash
./gradlew testDebugUnitTest coverageGate lintRelease verifyReleaseBudget stageRelease
# 产物：dist/kepan-clock-{arm64-v8a,x86_64}-release-<版本>.apk
adb install -r dist/kepan-clock-arm64-v8a-release-<版本>.apk
```

门禁：单测 + 覆盖率（domain ≥80%，实测 97.1%）+ lint 0 error + 包体 ≤12 MB + 签名校验。

## 版本记录

| 版本 | 内容 |
|---|---|
| 1.0.0(1) | 四模块骨架，最小可运行壳 |
| 1.1.0(2) | 历算内核全量 Kotlin 化，golden 差分与 Python 逐字节一致 |
| 1.2.1–1.2.4(4–7) | 渲染内核 1:1 迁移；竖屏五段式布局；字体子集入包；设备像素渲染根修 |
| 1.3.0(8) | 触摸命中、试时、Compose 设置牌窗、DataStore 写回热重载 |
| 1.4.0(9) | 报时链路（AlarmManager→前台服务）、设置牌窗风格重做、状态栏随主题 |
| 1.5.0(11) | 动态壁纸全量（三道功耗闸门）、题款字体统一、播放截断根修 |
| 1.6.0(12) | 壁纸示意稿精调、报时通知渠道静音根修、鼓声手机音高适配（320→130 Hz） |
| 1.6.1(13) | 天行印字号按框宽收敛（出框根修）；合规收尾（许可全文、脱敏校验） |
| 1.6.2(14) | 壁纸精调：瓦当章改神兽线描图案（不回退篆字）；底部读数三行统一字号行距并下移；天行印改篆书（全字庫說文解字）＋印章底色同壁纸底色 |
| 1.6.3(15) | 壁纸顶题组重排（篆书干支题「丙午年/己亥日」沿竖中线对称并列、节气名竖写居中、表盘外辰字环改篆）；篆书换華瑞小篆 4.2（北师大说文小篆系，非商用引用，GB2312 全汉字子集）；字体切换加篆书且全局生效；报时声改随包实录声样（桌面同源，合成兜底——「电子音」根因修复） |
| 1.6.4(16) | 壁纸「天行」印移至顶部、「立冬」竖写居中于原印章位（位置互换）；设置牌窗题款「天行健……」改篆书并居中 |

## 第三方组件与许可

| 组件 | 用途 | 许可 |
|---|---|---|
| AR PL UKai CN（子集 901 字） | 楷体（辰字/题款/正文） | Arphic Public License（随包 `assets/fonts/LICENSE-arphic-ukai.txt`） |
| 華瑞小篆體 4.2（GB2312 全汉字子集 6858 码位） | 篆书（节气/干支题/辰字环/印款/全局篆书切换；基底北師大說文小篆，疑似無人維護，作者聲明非商用） | 著作權狀況見随包 `assets/fonts/LICENSE-huarui-seal.txt`（本应用非商用引用） |
| androidx（Compose/DataStore/Lifecycle 等） | UI 与存储 | Apache License 2.0（随包 `META-INF/*/LICENSE.txt`） |
| Kotlin/Jetpack 标准库 | 语言与运行时 | Apache License 2.0 |

字体子集化：按用字范围 pyftsubset 生成，新增文案若出集需重新子集化
（源：系统 arphic ukai.ttc `--font-number=0`；華瑞小篆體 4.2 作者发布版，
FreeMdict 论坛 topic/45318，Google Drive 备用源）。

## 音频署名

钟/鼓声样为**随包实录 WAV**（`assets/sounds/bell.wav` 3.00 s、`drum.wav` 1.50 s，
22050 Hz 单声道 16-bit），与桌面版 `assets/sounds/` 同源同文件：
- bell.wav「Gong or bell vibrant (short)」— CC0（公有领域贡献），维基共享资源
- drum.wav「Bass drum」— CC BY-SA 3.0，作者 Clngre（随包再分发须附署名，见随包 `assets/sounds/声样来源.txt`）
合成 PCM（`StrikeAudio`）仅作声样缺失时的兜底（对齐桌面 `_ensure_sounds` 三级来源）。
报时前台通知为静默渠道（不播放系统提示音）。

## 隐私

应用不联网、不采集任何数据；观测点（纬度/经度/昏旦偏移）仅存储于本机
DataStore，用于日出日落与昏旦时刻计算。
