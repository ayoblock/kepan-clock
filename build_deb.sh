#!/bin/sh
# 刻盘钟 deb 打包脚本
# 用法：./build_deb.sh   产物：../kepan-clock_1.5.0-1_all.deb
set -e
cd "$(dirname "$0")"

VER=1.5.1
PKG=kepan-clock_${VER}-1_all
ROOT="pkgroot/${PKG}"

rm -rf pkgroot
mkdir -p "${ROOT}/DEBIAN" \
         "${ROOT}/usr/bin" \
         "${ROOT}/usr/share/kepan-clock" \
         "${ROOT}/usr/share/kepan-clock/sounds" \
         "${ROOT}/usr/share/applications" \
         "${ROOT}/usr/share/doc/kepan-clock" \
         "${ROOT}/usr/share/icons/hicolor"

# ---- DEBIAN/control ----
cat > "${ROOT}/DEBIAN/control" <<EOF
Package: kepan-clock
Version: ${VER}-1
Section: utils
Priority: optional
Architecture: all
Depends: python3 (>= 3.10), python3-gi, python3-gi-cairo, python3-cairo, gir1.2-gtk-4.0
Recommends: fonts-arphic-ukai
Installed-Size: 120
Maintainer: ayoblock <ayoblock@users.noreply.github.com>
Homepage: https://github.com/ayoblock/kepan-clock
Description: 刻盘钟·司辰 —— 十二时辰盘钟（GTK4）
 以天度为盘、以晷影为针的十二时辰桌面钟：辰字环、初正环、九十六刻环
 三重刻度，晷针一日一周合二十四时制；八刻副盘与更牌窗（五更二十五点，
 依日出日落与昏旦偏移定夜弧）；昼墨夜金双盘，三十秒渐变切换，星空依
 恒星日缓转，四象瓦当线描缀于四正；真太阳时读数、盘面试时（悬停读数）、
 更牌翻牌动画、历注气泡（三日条与整月历）、生肖水印、壁画全屏置底
 模式、报时声（可选）。右下「天行」字印设置牌窗：昼夜、剪影与淡度、
 辰字楷明、壁纸（壁画自启/静态快照），改即存热重载。
 内置农历月序表（1900-2100）与节气推步。
EOF

# ---- 启动器 ----
cat > "${ROOT}/usr/bin/kepan-clock" <<'EOF'
#!/bin/sh
# 刻盘钟启动器：固定用系统 Python（内置运行时无 GTK 绑定）
exec /usr/bin/python3 /usr/share/kepan-clock/app.py "$@"
EOF
chmod 755 "${ROOT}/usr/bin/kepan-clock"

# ---- 程序文件 ----
install -m 644 chen_core.py sun.py lunar.py lunar_data.py dial.py app.py \
        config.toml README.md "${ROOT}/usr/share/kepan-clock/"
# ---- 实录声样（随包，首次运行复制到 ~/.config/kepan-clock/sound/）----
cp sounds/bell.wav sounds/drum.wav sounds/声样来源.txt \
   "${ROOT}/usr/share/kepan-clock/sounds/"

# ---- .desktop ----
cat > "${ROOT}/usr/share/applications/cn.workbuddy.kepan.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=刻盘钟
GenericName=十二时辰盘钟
Comment=辰初正刻三环盘面，晷针一日一周，更牌随节气
Exec=kepan-clock
Icon=kepan-clock
Terminal=false
StartupWMClass=cn.workbuddy.kepan
Categories=Utility;Clock;
Keywords=clock;time;shichen;刻盘钟;时辰;
EOF

# ---- AppStream 元数据（软件中心资讯：简介/描述/截图）----
install -d "${ROOT}/usr/share/metainfo"
install -m 644 cn.workbuddy.kepan.metainfo.xml \
        "${ROOT}/usr/share/metainfo/"

# ---- 壁画模式桌面项 ----
cat > "${ROOT}/usr/share/applications/cn.workbuddy.kepan.wallpaper.desktop" <<'DESK'
[Desktop Entry]
Type=Application
Name=刻盘钟·壁画
GenericName=十二时辰盘钟壁画
Comment=全屏置底壁画：大盘居中、四角竖题、底部一行读数；Esc 退出
Exec=kepan-clock --wallpaper
Icon=kepan-clock
Terminal=false
StartupWMClass=cn.workbuddy.kepan.wallpaper
Categories=Utility;Clock;
Keywords=wallpaper;clock;shichen;壁画;刻盘钟;
DESK

# ---- 图标 ----
cp -r icons/hicolor/* "${ROOT}/usr/share/icons/hicolor/"

# ---- man 手册页 ----
mkdir -p "${ROOT}/usr/share/man/man1"
gzip -9 -c kepan-clock.1 > "${ROOT}/usr/share/man/man1/kepan-clock.1.gz"

# ---- postinst：装后刷新图标缓存 ----
cat > "${ROOT}/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
if command -v update-icon-caches >/dev/null 2>&1; then
    update-icon-caches /usr/share/icons/hicolor || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -f /usr/share/icons/hicolor || true
fi
EOF
chmod 755 "${ROOT}/DEBIAN/postinst"

# ---- 文档 ----
cp README.md "${ROOT}/usr/share/doc/kepan-clock/README.md"
cat > "${ROOT}/usr/share/doc/kepan-clock/copyright" <<'EOF'
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: kepan-clock
Source: local build

Files: *
Copyright: 2026 kepan-clock packagers
License: MIT
 本程序按 MIT 许可发布。程序不捆绑任何字体文件；
 篆体（字魂游龙篆书为试用版，商用需授权）须用户自备并自担授权之责。
EOF

# ---- 打包 ----
dpkg-deb --build --root-owner-group "${ROOT}" ../"${PKG}".deb
dpkg-deb --info "../${PKG}.deb" | head -20
echo "—— 产物：$(cd .. && pwd)/${PKG}.deb"
