# 刻盘钟 · 司辰

以天度为盘、以晷影为针的十二时辰盘钟。本仓库含同一机芯的两个实现：

| 平台 | 位置 | 版本 | 发布物 |
|---|---|---|---|
| **Linux 桌面**（GTK4） | [`docs/`](docs/README.md) ＋ [`src/`](src/) | 1.5.3 | `desktop-v*` Release 附件 `.deb` |
| **Android**（Compose） | [`android/`](android/README.md) | 1.6.4 | `android-v*` Release 附件 `.apk` |

## 下载

到 [Releases](https://github.com/ayoblock/kepan-clock/releases) 页按 tag 选取：

- `desktop-v1.x.x` —— 桌面版（`.deb` 包，Ubuntu 22.04+）
- `android-v1.x.x` —— 安卓版（`arm64-v8a` 与 `x86_64` 双 APK，Android 8.0+）

历史版本的 Release 长期保留，随时可回溯下载。

## 目录结构

```
├─ android/            Android 工程（app / render-core / wallpaper / domain-core）
├─ assets/             桌面随包资产（图标、实录钟鼓声样）
├─ docs/               桌面版 README、许可、发布说明、man 页
├─ packaging/          桌面打包（deb 构建脚本、AppStream 元数据）
├─ previews/           盘面预览与设计稿
├─ screenshots/        界面截图
└─ src/                桌面源码（Python + GTK4）
```

## 平台文档

- 桌面版功能与构建：[`docs/README.md`](docs/README.md)
- 安卓版功能与构建：[`android/README.md`](android/README.md)

## 名例

观天之道，执天之行，尽矣！

## 许可

MIT（见 [docs/LICENSE](docs/LICENSE)）；随包字体与声样另有各自许可，见各平台 README 的许可表。
