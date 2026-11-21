# 刻盘钟 1.5.3 · 司辰

## 修复设置牌窗「昼夜」三选不起作用（本轮主项）

### 根因（逐层实测，隔离 HOME 不触碰真实配置）

固定时刻 2026-11-14 22:38（夜间），对配置管线逐层测量：

| 层 | 实测结果 | 判定 |
|---|---|---|
| 命中 | `dial.hit_test` 对「常昼／常夜」按钮中心返回 `set:theme:day` / `set:theme:night` | 正常 |
| 写盘 | `save_cfg_value("mode","theme","day")` 落盘为 `day`，`load_config()` 读回 `day` | 正常 |
| 热重载 | `os.replace` 原子写触发 GFileMonitor 的 DELETED(2)/CREATED(3)/CHANGES_DONE_HINT(1)；连续 4 次写入共 12 事件，逐次落盘正确 | 正常 |
| **状态计算** | `theme=auto / day / night` 三者均为 `night=True`、`blend=1.000`、色板哈希同为 `473b07419e` | **断点** |
| 对照组（CLI） | `force_night=False/True` 得 `blend=0.000/1.000`、色板 `a8e9e256c7` / `473b07419e` | 渲染输入链路正常 |

`app.py::compute_state()` 决定 `night` 时只判断 `force_night`（命令行 `--day/--night`），**从未读取 `cfg["mode"]["theme"]`**；该配置值仅在 `dial.py` 用于绘制设置牌窗的选中态。因此点击「常昼／常夜」后按钮高亮会变、配置也会落盘，盘面昼夜却始终随日出日落。这是接线缺失，不是渲染层或热重载缺陷。

### 改动

- `compute_state()` 读取 `mode.theme`，昼夜优先级明确为 **CLI 强制 > 配置主题 > 日出日落**；主题值经 `.strip().lower()` 归一，未知值兜底为 `auto`。
- 三十秒昼夜过渡仅在 `auto` 时计算：锁定「常昼／常夜」后在日出日落边界不再透出过渡色。CLI 强制的既有行为不变。
- 一处修复同时覆盖时钟窗口、壁画窗口与 `--png` 离屏渲染（三者共用 `compute_state`）。
- 文档同步：README 疑难条目与设置牌窗说明、man 页（`--day/--night` 优先级、版本号）、AppStream release。

### 验证（全过）

| 项 | 结果 |
|---|---|
| R1 夜间 22:38 | auto 夜盘不变；day 切 `blend=0.000`、night 保持 `1.000`；色板分别与 CLI `--day` / `--night` 一致 |
| R2 白天 10:00 | night 强制切夜盘；auto/day 仍为昼盘 |
| R3 优先级与兜底 | CLI 强制压过配置；未知主题值兜底 auto；`"  DAY  "` 归一生效 |
| R4 日落边界（日落 1019.77 min + 0.25 min） | auto `blend=0.750` 保留过渡；day `0.000`、night `1.000` 不被覆盖 |
| R5 真实点击链路 | 坐标 → `hit_test` → `_clicked` → `_on_setting` → 落盘 → `load_config` → `compute_state`：点常昼得 `night=False`、点常夜得 `night=True` |
| R6 像素取证 | 固定时刻渲染：`theme=day` 与 `--day` PNG md5 同为 `2813531271`；`theme=night` 与 `--night` 同为 `a03d045ded`；昼夜两图确有差异 |
| 其它 | `py_compile` 通过；`--png` 离屏自检 4 张（含 `--day` / `--night` / 设置牌窗）正常 |

本次未改 `config.toml` 默认值；项目与 `/usr/share/kepan-clock/config.toml` md5 一致，用户级配置为既有自定义（`sound.enabled`、水印、字体链），deep-merge 兜底正常。声音模块未改动，故未重跑 `--sound-test`（避免约 10 秒外放）。

### 升级注意

本机已装 1.5.2-1，进程自 11-14 22:07 运行，加载的是修复前的代码；**安装后须完全退出旧进程再启动**。若以 `--day` / `--night` 命令行启动，设置里的昼夜三选按设计不生效。

## 安装

本机构建产物（绝对路径）：

```bash
sudo dpkg -i kepan-clock_1.5.3-1_all.deb
```

从 GitHub Releases 下载时，把路径换成实际下载位置。

sha256: `3e2bc9c0e5de7e2e812c1947f6a7fe21a48a0c5919f2c0e4420d23290e813327`
