package cn.workbuddy.kepanclock.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cn.workbuddy.kepanclock.render.Palette
import cn.workbuddy.kepanclock.render.SceneRenderer
import cn.workbuddy.kepanclock.render.SceneState
import java.io.File
import java.time.LocalDateTime

/**
 * 静态壁纸快照：按壁画构图（drawWallpaper）渲染当前时刻整屏位图，
 * 存入系统 Pictures（API 29+ MediaStore，低版本落 app 外存并回路径）。
 */
object WallpaperSnapshot {

    /** 导出快照，返回可展示给用户的落盘说明。 */
    fun export(context: Context, st: SceneState, fonts: cn.workbuddy.kepanclock.render.Primitives.Fonts): String {
        val dm = context.resources.displayMetrics
        val dpr = dm.density
        val wDp = dm.widthPixels / dpr
        val hDp = dm.heightPixels / dpr
        val pal = Palette.lerp(Palette.day(), Palette.night(), st.blend)

        val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.save()
        c.scale(dpr, dpr)
        SceneRenderer.drawWallpaper(
            c, wDp.toDouble(), hDp.toDouble(), st, pal, fonts, dpr.toDouble(),
        )
        c.restore()

        val name = "kepan-wall-%s.png".format(
            LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")),
        )
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return "导出失败：无法写入 Pictures"
            context.contentResolver.openOutputStream(uri)?.use { os ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            } ?: return "导出失败：流不可写"
            "已存入 Pictures/$name"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            val f = File(dir, name)
            f.outputStream().use { os -> bmp.compress(Bitmap.CompressFormat.PNG, 100, os) }
            "已存入 ${f.absolutePath}"
        }
    }
}
