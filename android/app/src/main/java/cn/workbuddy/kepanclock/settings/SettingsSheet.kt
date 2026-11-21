package cn.workbuddy.kepanclock.settings

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.workbuddy.kepanclock.render.Palette

/**
 * 设置牌窗（§9-3 拍板：设置页 Compose，与 ClockView 平级浮于底部）。
 * 样式与 app 整体风格一致：底色/墨色/朱/描边全部取自渲染层 Palette
 * （昼/夜两套，night 参数跟随盘面生效主题），不另起 Material 配色。
 * 对照桌面 _popup_settings：昼夜三选 / 剪影五选 / 淡度滑轨 / 字体三选（楷明篆，
 * 2026-11-21 用户拍板扩展：全局切换所有文字）/ 报时开关（P4）；壁纸动作（壁画自启/静态壁纸）属 P4 壁纸轮，本版不出现。
 * 「改即存」：每项改动即时回调落 DataStore，Flow 发射即刻生效。
 * 观测点（纬度/经度/昏旦偏移）为桌面 config.toml 手工项的承接，输入校验后写回。
 */
@Composable
fun SettingsSheet(
    cfg: KepanConfig,
    night: Boolean,
    onTheme: (String) -> Unit,
    onWatermark: (String) -> Unit,
    onAlpha: (Double) -> Unit,
    onFont: (String) -> Unit,
    onSound: (Boolean) -> Unit,
    onLiveWallpaper: () -> Unit,
    onSnapshot: () -> Unit,
    onPlace: (Double, Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = if (night) Palette.night() else Palette.day()
    val bg = Color(Palette.argb(pal["bg"]))
    val ink = Color(Palette.argb(pal["ink"]))
    val faint = Color(Palette.argb(pal["faint"]))
    val hair = Color(Palette.argb(pal["hair"]))
    val cinnabar = Color(Palette.argb(pal["cinnabar"]))

    // 题款用内置楷体子集（与盘面辰字同源）
    val context = LocalContext.current
    val kai = remember {
        runCatching { FontFamily(Typeface.createFromAsset(context.assets, "fonts/UKai.ttf")) }
            .getOrDefault(FontFamily.Default)
    }
    // 题款篆书：華瑞小篆子集（2026-11-22 用户拍板，题款换篆书）
    val seal = remember {
        runCatching { FontFamily(Typeface.createFromAsset(context.assets, "fonts/HuaRuiSeal.ttf")) }
            .getOrDefault(kai)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 10.dp,
        color = bg,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "天行健，君子以自强不息",
                fontFamily = seal,
                fontSize = 17.sp,
                color = cinnabar,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
            )

            LabeledRow("昼夜", faint) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingChip("随昼夜", cfg.theme == "auto", hair, ink, cinnabar) { onTheme("auto") }
                    SettingChip("常昼", cfg.theme == "day", hair, ink, cinnabar) { onTheme("day") }
                    SettingChip("常夜", cfg.theme == "night", hair, ink, cinnabar) { onTheme("night") }
                }
            }

            LabeledRow("剪影", faint) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingChip("生肖", cfg.watermark == "zodiac", hair, ink, cinnabar) { onWatermark("zodiac") }
                        SettingChip("飞天", cfg.watermark == "feitian", hair, ink, cinnabar) { onWatermark("feitian") }
                        SettingChip("神兽", cfg.watermark == "beast", hair, ink, cinnabar) { onWatermark("beast") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingChip("辰字", cfg.watermark == "chen", hair, ink, cinnabar) { onWatermark("chen") }
                        SettingChip("无", cfg.watermark == "none", hair, ink, cinnabar) { onWatermark("none") }
                    }
                }
            }

            LabeledRow("淡度（${"%.2f".format(cfg.wmAlpha)}）", faint) {
                Slider(
                    value = cfg.wmAlpha.toFloat(),
                    onValueChange = { onAlpha(it.toDouble()) },
                    valueRange = KepanConfig.ALPHA_RANGE.start.toFloat()
                        ..KepanConfig.ALPHA_RANGE.endInclusive.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = cinnabar,
                        activeTrackColor = cinnabar,
                        inactiveTrackColor = hair,
                    ),
                )
            }

            LabeledRow("字体", faint) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingChip("楷体", cfg.font == "kai", hair, ink, cinnabar) { onFont("kai") }
                    SettingChip("明体", cfg.font == "ming", hair, ink, cinnabar) { onFont("ming") }
                    SettingChip("篆书", cfg.font == "zhuan", hair, ink, cinnabar) { onFont("zhuan") }
                }
            }

            LabeledRow("报时", faint) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingChip("关", !cfg.soundEnabled, hair, ink, cinnabar) { onSound(false) }
                    SettingChip("开（每刻钟鼓）", cfg.soundEnabled, hair, ink, cinnabar) { onSound(true) }
                }
            }

            LabeledRow("壁纸", faint) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingChip("动态壁画", false, hair, ink, cinnabar) { onLiveWallpaper() }
                    SettingChip("静态快照", false, hair, ink, cinnabar) { onSnapshot() }
                }
            }

            PlaceEditor(cfg = cfg, ink = ink, faint = faint, hair = hair, cinnabar = cinnabar, onPlace = onPlace)
        }
    }
}

@Composable
private fun LabeledRow(label: String, faint: Color, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 13.sp,
            color = faint,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(0.16f),
        )
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun SettingChip(
    label: String,
    selected: Boolean,
    hair: Color,
    ink: Color,
    cinnabar: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Text(
        label,
        fontSize = 12.sp,
        color = if (selected) cinnabar else ink,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) cinnabar.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (selected) cinnabar else hair, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** 观测点编辑：本地草稿态，输入合法即写回（非法不落盘）。 */
@Composable
private fun PlaceEditor(
    cfg: KepanConfig,
    ink: Color,
    faint: Color,
    hair: Color,
    cinnabar: Color,
    onPlace: (Double, Double, Double) -> Unit,
) {
    var latText by remember(cfg.lat) { mutableStateOf(cfg.lat.toString()) }
    var lonText by remember(cfg.lon) { mutableStateOf(cfg.lon.toString()) }
    var duskText by remember(cfg.duskMin) { mutableStateOf(cfg.duskMin.toString()) }

    fun tryCommit() {
        val lat = KepanConfig.parseLatitude(latText)
        val lon = KepanConfig.parseLongitude(lonText)
        val dusk = KepanConfig.parseDusk(duskText)
        if (lat != null && lon != null && dusk != null) onPlace(lat, lon, dusk)
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = ink,
        unfocusedTextColor = ink,
        cursorColor = cinnabar,
        focusedBorderColor = cinnabar,
        unfocusedBorderColor = hair,
        focusedLabelColor = cinnabar,
        unfocusedLabelColor = faint,
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("观测点", fontSize = 13.sp, color = faint)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it; tryCommit() },
                label = { Text("纬度") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lonText,
                onValueChange = { lonText = it; tryCommit() },
                label = { Text("经度") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = duskText,
            onValueChange = { duskText = it; tryCommit() },
            label = { Text("昏旦偏移（分钟，约 37）") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
