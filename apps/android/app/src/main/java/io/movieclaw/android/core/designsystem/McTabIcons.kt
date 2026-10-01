package io.movieclaw.android.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 底栏图标 —— 直接照 MovieClaw-iOS端复刻.html 的 SVG 精灵逐条路径搬过来
 * （24×24 视口、描边 1.7、圆头圆角），保证与原型一一对应：
 *   房子（发现）/ 播放方块（媒体库）/ 书签（订阅）/ 心电（活动）。
 * 「我的」格在底栏里是账号头像，不用图标。
 */
object McTabIcons {

    private fun build(name: String, vararg paths: Pair<String, Boolean>): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        paths.forEach { (data, filled) ->
            val nodes = PathParser().parsePathString(data).toNodes()
            builder.addPath(
                pathData = nodes,
                fill = if (filled) SolidColor(Color.White) else null,
                stroke = if (filled) null else SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }

    /** #i-house：屋顶一条线 + 屋身，无门 */
    val House: ImageVector by lazy {
        build(
            "mc-home",
            "M3 10.5 12 3l9 7.5" to false,
            "M5.5 9.5V20h13V9.5" to false,
        )
    }

    /** #i-lib：圆角矩形 + 内嵌播放三角 + 后面叠一层的第二张 */
    val Library: ImageVector by lazy {
        build(
            "mc-library",
            "M6 4h8a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3z" to false,
            "M20 7v11a2 2 0 0 1-2 2h-1" to false,
            "m9 9.5 4 2.5-4 2.5z" to true,
        )
    }

    /** #i-bookmark：书签（下缘 V 形缺口） */
    val Bookmark: ImageVector by lazy {
        build(
            "mc-bookmark",
            "M7 4h10a1 1 0 0 1 1 1v15l-6-4-6 4V5a1 1 0 0 1 1-1z" to false,
        )
    }

    /** #i-wave：心电折线 */
    val Wave: ImageVector by lazy {
        build(
            "mc-wave",
            "M3 12h3l2-5 3 10 2.5-7 2 4h3.5" to false,
        )
    }
}
