package cn.workbuddy.kepanclock

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.workbuddy.kepanclock.app.ClockActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P0 仪器冒烟：验证 ClockActivity 可启动且内容视图非空。
 * 当前连接 2 台设备，运行时必须用 ANDROID_SERIAL 限定单设备（§5 假阳性陷阱）。
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test
    fun clockActivity_launches_with_content_view() {
        ActivityScenario.launch(ClockActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Activity 应有内容视图", activity.window.decorView)
            }
        }
    }
}
