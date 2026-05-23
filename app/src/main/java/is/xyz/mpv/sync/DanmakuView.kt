package `is`.xyz.mpv.sync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Danmaku(
        val text: String,
        val color: Int,
        var x: Float,
        val lane: Int,
        val speed: Float,
        val width: Float
    )

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dpToPx(20f)
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val danmakus = mutableListOf<Danmaku>()
    private var laneCount = 6
    private val laneHeight = dpToPx(28f)
    private val laneLastRight = FloatArray(20) { 0f } // Keep track of the rightmost position in each lane

    private var lastFrameTime: Long = 0

    init {
        // Set transparent background
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val usableHeight = h * 0.45f // Only use top 45% of the screen
        laneCount = (usableHeight / laneHeight).toInt().coerceIn(3, 20)
    }

    fun addDanmaku(text: String, colorStr: String) {
        post {
            val color = try {
                Color.parseColor(colorStr)
            } catch (e: Exception) {
                Color.WHITE
            }

            val textWidth = textPaint.measureText(text)
            val screenWidth = width.toFloat()

            // Find best lane
            var bestLane = 0
            var minRight = Float.MAX_VALUE
            for (i in 0 until laneCount) {
                val right = laneLastRight[i]
                if (right < minRight) {
                    minRight = right
                    bestLane = i
                }
            }

            // If the best lane is still occupied past the screen edge, add a tiny bit of gap
            val startX = if (minRight > screenWidth) {
                minRight + dpToPx(16f)
            } else {
                screenWidth
            }

            // Speed range: 150dp/s to 250dp/s
            val speedDpSec = Random.nextFloat() * 100f + 150f
            val speed = dpToPx(speedDpSec)

            val danmaku = Danmaku(
                text = text,
                color = color,
                x = startX,
                lane = bestLane,
                speed = speed,
                width = textWidth
            )

            danmakus.add(danmaku)
            laneLastRight[bestLane] = startX + textWidth

            if (danmakus.size == 1) {
                lastFrameTime = System.nanoTime()
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (danmakus.isEmpty()) return

        val now = System.nanoTime()
        val deltaSec = if (lastFrameTime == 0L) 0f else (now - lastFrameTime) / 1_000_000_000f
        lastFrameTime = now

        // Update positions & draw
        val iterator = danmakus.iterator()
        while (iterator.hasNext()) {
            val danmaku = iterator.next()
            danmaku.x -= danmaku.speed * deltaSec

            // Draw text
            textPaint.color = danmaku.color
            val y = (danmaku.lane + 1) * laneHeight
            canvas.drawText(danmaku.text, danmaku.x, y, textPaint)

            // Remove if off screen
            if (danmaku.x + danmaku.width < 0) {
                iterator.remove()
            }
        }

        // Recalculate lane rights
        for (i in 0 until laneCount) {
            laneLastRight[i] = 0f
        }
        for (d in danmakus) {
            val right = d.x + d.width
            if (right > laneLastRight[d.lane]) {
                laneLastRight[d.lane] = right
            }
        }

        if (danmakus.isNotEmpty()) {
            postInvalidateOnAnimation()
        } else {
            lastFrameTime = 0
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
