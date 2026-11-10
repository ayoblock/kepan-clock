# 刻盘钟 · 司辰

十二时辰盘钟的 Linux 桌面程序（1.4.3），MIT 许可。以「以天度为盘、以晷影为针」为制：

- **正名「刻盘钟」，铭款「司辰」，俗称「时盘」**——正名入文书，铭款刻于器，俗称行于口。
- 主盘三重刻度：一辰三十度，初正十五度，九十六刻一刻三点七五度；辰字朱填居辰之中，午上子下、卯左酉右，晷针顺时针行。
- 机芯合二十四时制：晷针一日一夜一周，故子时一日只现一次，昼夜可辨。
- 八刻副盘：刻针一辰（两小时）一周，盘八等分标一至八，盘心生肖篆字随辰轮换。
- 更牌窗：五更二十五点，依当日日出日落与昏旦偏移（默认三十七分钟，约古二刻半）定夜弧；白昼显「昼」，纬度缺失显「更点缺」。
- 昼盘／夜盘：昼素底墨字，夜暗底金字加星空弱纹；切换有三十秒渐变；星空依恒星日（二十三时五十六分）缓转，主星三等、附银河弱带；素环四正缀四象瓦当线描（午朱雀、酉白虎、子玄武、卯苍龙）。
- 真太阳时：面板示真太阳时读数（时差＋经度改正），与「以晷影为针」的制义相合。
- 盘面试时：悬停主盘任意角位，牌窗即时显示该位读数。
- 更牌翻牌：更点交替时半秒翻牌，仿机械牌轮。
- 报时声（默认关）：辰初钟三下、辰正鼓二下、每刻鼓一声；config `[sound] enabled = true` 开启，须有 `paplay`。
- 声样目录 `~/.config/kepan-clock/sound/`：随包附真实钟鼓录音（钟「Gong or bell vibrant (short)」CC0；鼓「Bass drum」CC BY-SA 3.0，作者 Clngre，来源维基共享资源，署名见目录内《声样来源.txt》）。放入自备的同名 `bell.wav` / `drum.wav`（单声样 16-bit WAV，任意采样率）即自动重组三件报时声，删除事件文件或重启生效。
- 历注与气泡（1.4.0）：真太阳时下一行常驻历注「年干支 · 农历月日 · 日干支 · 候」，点按弹出素纸气泡——一级为昨／今／明三日条，底栏「展全月」展开二级整月历（朔日与节气朱标、今日朱框），点历注行或按 Esc 收起，点气泡外亦收。星期以七政曜日名之（日月火水木金土，日曜为周日）。
- 生肖水印（1.4.0）：右列背景以当前时辰生肖剪影作低透明度水印（随辰换兽），可选四神兽 / 辰字大篆 / 无，透明度 `panel.watermark_alpha` 可调。1.4.3 起生肖与四神兽同样解除定尺：等比缩放居中，占满更牌窗之下至副盘上缘的区域（辰字大篆仍按小表盘同径）。
- 飞天水印（1.4.3）：`panel.watermark = "feitian"` 切为当前时辰飞天剪影（随辰换姿，十二姿各异——右飞、仰升、俯落、横卧、回望等），并解除定尺：占满更牌窗之下至副盘上缘的区域，窗越大像越大。剪影为部件拼装自绘（贝塞尔粗线），无版权顾虑。
- 壁画模式（1.4.0）：`kepan-clock --wallpaper` 启动全屏置底窗——大盘居中（径 0.72×短边），四角竖题（左上款识、右上映候、左下日干支、右下年干支），底部一行「时辰读数 · 更点 · 历注」；平稳时六秒一换，昼夜渐变期十帧；Esc 退出。应用号独立（`cn.workbuddy.kepan.wallpaper`），可与常驻时钟同时运行。
- 设置牌窗（1.5.0）：右下「天行」篆字印点开（或再点／Esc 收起）——昼夜三选（随昼夜／常昼／常夜）、剪影五选（生肖／飞天／神兽／辰字／无）与淡度滑轨（按住可拖）、辰字楷明二选、壁纸动作（「壁画自启」写开机自启项并即时拉起壁画窗，再点取消；「静态壁纸」渲当前时刻快照并经 gsettings 设为桌面壁纸，无 gsettings 时只存 PNG）。改动即写用户配置（保留注释），半秒热重载生效。
- 内置农历（1.4.0）：`lunar.py` 自推月序表（1900–2100）＋ Meeus 节气，春节 11 例、闰月 5 例、干支锚点、节气、候与 400 日连续性自校全过；不依赖第三方历算库。

## 环境要求

- Linux + GTK4（Ubuntu 22.04 及以上）；Python 3.10+
- 依赖包：`python3-gi`、`python3-gi-cairo`、`python3-cairo`、`gir1.2-gtk-4.0`
- 中文字体：楷体（`fonts-arphic-ukai`，推荐）；篆体自备（见下）

## 安装（deb）

从 [Releases](https://github.com/ayoblock/kepan-clock/releases) 下载 `kepan-clock_1.5.0-1_all.deb` 直接安装；或按「源码运行」与文末「打包」自行构建：

```sh
sudo dpkg -i kepan-clock_1.5.0-1_all.deb
sudo apt-get install -f   # 若缺依赖
```

安装后从应用菜单启动「刻盘钟」，或命令行运行 `kepan-clock`；壁画模式可从菜单启动「刻盘钟·壁画」。

## 源码运行（不安装）

```sh
/usr/bin/python3 app.py
```

> 注意：须用系统 Python（`/usr/bin/python3`）。某些环境（如 WorkBuddy 沙箱）的默认
> `python3` 是内置运行时，无 GTK 绑定，直接运行会报 `ModuleNotFoundError: gi`。

命令行开关：

```sh
kepan-clock --day            # 锁定昼盘
kepan-clock --night          # 锁定夜盘
kepan-clock --wallpaper      # 壁画模式（全屏置底，Esc 退出）
kepan-clock --png out.png --hhmm 15:45   # 离屏渲染自检图后退出
kepan-clock --png wp.png --wallpaper --size 1920x1080    # 壁画预览图
```

## 配置

复制 `config.toml` 到 `~/.config/kepan-clock/config.toml` 后修改：

| 项 | 默认 | 说明 |
|---|---|---|
| `place.latitude / longitude` | 39.9 / 116.4（北京） | 更点与昼夜切换所依。**不设则更点不可信**，牌窗标「更点缺」 |
| `dusk.offset_minutes` | 37 | 昏＝日落加此数，旦＝日出减此数 |
| `mode.theme` | auto | auto 随日出日落 / day / night |
| `fonts.chen` | 楷体链 | 辰字与读数 |
| `fonts.seal` | 游龙篆书链 | 款识、四象、节气、生肖 |
| `panel.watermark` | zodiac | 右列背景水印：zodiac 生肖剪影 / feitian 飞天剪影（随辰换姿，占满更牌下区域）/ beast 四神兽 / chen 辰字大篆 / none |
| `panel.watermark_alpha` | 0.16 | 水印不透明度（0.02–0.45） |

## 字体说明（重要）

- 篆体默认首选**字魂游龙篆书**（试用版，文件名自注商用需授权），兜底为开源的
  **霞鹜篆书（LXGW Seal）**，再退美人篆、楷体。
- 本程序**不捆绑任何字体文件**：仓库与发行包均不含字体，只按字体名引用系统已装字体。
  若要公开分发或再分发，请自行确认所装篆体的授权范围，或改用 LXGW Seal（OFL 开源）。
  缺篆体时程序自动回退楷体：功能不受影响，款识失古意而已。

## 算法与精度

- 时刻换算：u = (T+60) mod 1440 折日界于前夜二十三时；辰序 = u÷120，p<60 为初、
  否则为正，刻 = ÷15 取整加一。自校用例 11 个，见 `chen_core.py` 末尾。
- 日出日落：NOAA 算法，地平含 -0.833° 折射修正，精度约 ±1 分钟。
- 节气：Meeus 低阶太阳视黄经，精度约 ±5 分钟，足够「换气环片」之用。
- 刷新：每秒对齐整秒触发（非固定间隔），休眠唤醒后按当前时刻整体重算，不累积误差。

## 疑难

- **报时声（连同钟面）长期运行后停摆**：1.4.1 之前，tick 回调里任何未捕获异常都会让
  GLib 把这条定时链整条拆掉——表现为钟面冻结、报时不再响，且从桌面启动看不到报错。
  1.4.1 起回调整体兜底：异常写入 `~/.config/kepan-clock/error.log`（超 64 KiB 自动
  清空重记），下一跳照常重挂，钟面用上一次状态渲染不停摆；声样改为先写临时文件再
  原子替换，热重载并发也读不到半截 WAV；报时连续失败会自动重建声样，持续失败转逐
  次诊断并打印 paplay 的报错；休眠唤醒只补当前一刻，不再补播错过的几十声。若你在
  旧版遇到停摆，升级即可；排查历史异常看 `error.log`。
- **图标不刷新**：安装后运行
  `sudo update-icon-caches /usr/share/icons/hicolor`，或注销重登一次。
  无 root 时程序图标已同步装入 `~/.local/share/icons/hicolor`，运行
  `gtk-update-icon-cache -f ~/.local/share/icons/hicolor` 即可。
- **篆体疑未生效**：从终端运行 `kepan-clock`，启动时会自检篆体链：
  未生效则打印「警告：篆体未生效」。常见原因是字体未装在
  `~/.local/share/fonts`，装后跑一次 `fc-cache -f` 再启动。
- **想让主盘辰字也用篆体**：辰字按前定用楷体（篆书远读辨识差）。
  若要改，编辑 `~/.config/kepan-clock/config.toml`：
  `[fonts]` 下 `chen = "字魂游龙篆书(商用需授权), AR PL UKai CN, serif"`。


### 报时声无声排查

节律：**每刻一声（每十五分钟一次鼓），辰初（每两小时）钟三下，辰正鼓二下**——两声之间本就有最长十五分钟的静默，非故障。验证与排查：

```sh
kepan-clock --sound-test      # 立即播钟三下并打印全链路诊断
```

- 命令报「paplay 退出码 0」而耳中无声 → 查系统音量与输出设备；
- 命令报 paplay 缺失 → `sudo apt install pulseaudio-utils`；
- 想看每次触发：config `[sound]` 段加 `debug = true`，从终端启动 `kepan-clock` 可见触发行。

## 已知限制

- 更点以昏旦均分五更，与官历漏刻随节气增减之制略有出入（设计取舍，非疏漏）。
- 盘刻（一日九十六）与历刻（一日一百）双制并行，程序内只用盘刻；对文书换算须
  参照钟册首页对照表（1 盘刻 ≈ 1.0417 历刻）。
- 无系统托盘常驻（Unity 无托盘约定）；置顶等窗口行为交由窗口管理器。
- 壁画模式在 Wayland 下无 layer-shell，取「全屏＋无饰＋映射时沉底」近似；
  被点击激活后仍在普通窗口层之下不保，若被其他窗盖住，点其图标重新唤起即全屏。
  月序表边界日（1900-01-31 前后、2100 年末）不保证；节气日内边界约 ±15 分钟，
  折合历注候名极端情况可能差一日。

## 文件结构

```
kepan_clock/
├── chen_core.py   # 时刻→辰/初正/刻 纯函数＋自校
├── sun.py         # 日出日落、节气、更点＋自校
├── lunar_data.py  # 农历月序表（1900–2100，自推）
├── lunar.py       # 农历、干支、节气日、七十二候、历注＋自校
├── dial.py        # 盘面与壁画渲染（GTK4 与离屏 PNG 共用）
├── app.py         # GTK4 主程序＋壁画模式＋CLI
├── config.toml    # 默认配置
├── kepan-clock.1  # man 页
├── build_deb.sh   # 打包脚本
├── gen_icon.py    # 图标生成
├── icons/         # hicolor 图标集
├── sounds/        # 报时声样（CC0 与 CC BY-SA 3.0，见 NOTICE）
├── 预览-*.png     # 盘面、气泡、壁画预览
├── LICENSE        # MIT
└── NOTICE         # 音频与字体的例外说明
```

## 自检

```sh
/usr/bin/python3 chen_core.py   # 11 例换算
/usr/bin/python3 sun.py         # 日出日落、节气、更点
/usr/bin/python3 lunar.py       # 春节/闰月/干支/节气/候/连续性
/usr/bin/python3 app.py --png /tmp/day.png --hhmm 15:45 --day
/usr/bin/python3 app.py --png /tmp/night.png --hhmm 02:30 --night
/usr/bin/python3 app.py --png /tmp/pop.png --popup month   # 整月历气泡
```

## 打包

```sh
sh build_deb.sh      # 产物：../kepan-clock_1.4.3-1_all.deb
```

需 `dpkg-deb`（`dpkg-dev` 包）。脚本按 `pkgroot/` 组织目录树、写 control 与 postinst、
刷新图标缓存，不触碰 `~/.config/kepan-clock/` 下的用户配置。

## 名例

> 正名「刻盘钟」，铭款「司辰」，俗称「时盘」；正名入文书，铭款刻于器，俗称行于口，三场各守，不得混用。

盘面页脚现刻《阴符经》句：**观天之道，执天之行，尽矣！**

## 许可

- **源代码**：MIT，全文见 [LICENSE](LICENSE)。
- **报时声样** `sounds/`：钟声 CC0 公有领域；鼓声 CC BY-SA 3.0（作者 Clngre）。此目录保留原许
  可，不受 MIT 影响，再分发须署名并沿用 CC BY-SA 3.0。
- **字体**：仓库不含任何字体文件，程序按字体名引用系统已装字体。默认首选的「字魂游龙篆书」为
  商业试用字体，商用须另行授权；开源替代为「霞鹜篆书 LXGW Seal」（SIL OFL 1.1）。

以上例外条款详见 [NOTICE](NOTICE)。
