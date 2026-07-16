package com.pdy.tvpro.util

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * 弹窗尺寸统一计算，适配横屏 / 竖屏，避免竖屏下列表过高、过窄或超出屏幕。
 */
object PopupLayoutHelper {

    data class Metrics(
        val screenWidth: Int,
        val screenHeight: Int,
        val isPortrait: Boolean,
        /** 弹窗宽度 */
        val popupWidth: Int,
        /** 弹窗最大高度（超出时依赖内部 ListView 滚动） */
        val popupMaxHeight: Int,
        /** 主文字像素字号 */
        val fontSize: Int,
        /** 设置列表行高 */
        val rowHeight: Int,
        /** 底部贴边弹窗建议高度 */
        val bottomSheetHeight: Int,
        /** 对话框宽度 */
        val dialogWidth: Int,
        /** 对话框按钮高度 */
        val dialogButtonHeight: Int
    )

    fun metrics(context: Context): Metrics {
        val dm = resolveDisplayMetrics(context)
        val w = dm.widthPixels
        val h = dm.heightPixels
        val isPortrait = h > w
        val shortSide = min(w, h)
        val longSide = max(w, h)

        // 字号按长边基准，竖屏不会因宽度变小而字过小；并限制上下限
        val fontSize = (longSide / 42).coerceIn(18, 48)

        // 行高：按短边均分，竖屏略密一些，避免单项过高导致一屏显示不全
        val rowDivisor = if (isPortrait) 16 else 12
        val rowHeight = (shortSide / rowDivisor - 2).coerceIn(fontSize * 2, fontSize * 3)

        val popupWidth = if (isPortrait) {
            (w * 0.92f).toInt()
        } else {
            (w * 0.5f).toInt()
        }

        val popupMaxHeight = if (isPortrait) {
            (h * 0.72f).toInt()
        } else {
            (h * 0.85f).toInt()
        }

        val bottomSheetHeight = if (isPortrait) {
            (h * 0.55f).toInt()
        } else {
            (h * 2 / 3)
        }

        val dialogWidth = if (isPortrait) {
            (w * 0.9f).toInt()
        } else {
            w * 3 / 5
        }

        val dialogButtonHeight = (shortSide / 10).coerceIn(fontSize * 2, fontSize * 4)

        return Metrics(
            screenWidth = w,
            screenHeight = h,
            isPortrait = isPortrait,
            popupWidth = popupWidth,
            popupMaxHeight = popupMaxHeight,
            fontSize = fontSize,
            rowHeight = rowHeight,
            bottomSheetHeight = bottomSheetHeight,
            dialogWidth = dialogWidth,
            dialogButtonHeight = dialogButtonHeight
        )
    }

    private fun resolveDisplayMetrics(context: Context): DisplayMetrics {
        val dm = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(dm)
        } else {
            val res = context.resources.displayMetrics
            dm.widthPixels = res.widthPixels
            dm.heightPixels = res.heightPixels
            dm.density = res.density
        }
        // 兜底：异常 0 尺寸时用 resources
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            val res = context.resources.displayMetrics
            dm.widthPixels = res.widthPixels
            dm.heightPixels = res.heightPixels
        }
        return dm
    }
}