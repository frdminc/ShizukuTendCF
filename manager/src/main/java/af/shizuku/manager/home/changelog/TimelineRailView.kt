package af.shizuku.manager.home.changelog

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Vertical rail with a glowing node near the top, one per timeline row. The rail runs through
 * the middle of the view; the first row starts at its node and the last row ends at it.
 */
class TimelineRailView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private val density = resources.displayMetrics.density
        private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density }
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
            }

        private var haloScale = 1f
        private var pulse: ValueAnimator? = null

        var isFirst = false
        var isLast = false
        var nodeCenterY = 16f * density

        fun setColors(
            rail: Int,
            node: Int,
            halo: Int,
        ) {
            railPaint.color = rail
            nodePaint.color = node
            ringPaint.color = node
            haloPaint.color = halo
            invalidate()
        }

        /** Filled node = a release you can act on / are on; hollow ring = history. */
        var filled = true

        fun setPulsing(enabled: Boolean) {
            pulse?.cancel()
            pulse = null
            haloScale = 1f
            if (enabled && isAttachedToWindow) {
                pulse =
                    ValueAnimator.ofFloat(1f, 1.5f).apply {
                        duration = 1400
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = LinearInterpolator()
                        addUpdateListener {
                            haloScale = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            pulse?.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val top = if (isFirst) nodeCenterY else 0f
            val bottom = if (isLast) nodeCenterY else height.toFloat()
            if (bottom > top) canvas.drawLine(cx, top, cx, bottom, railPaint)
            canvas.drawCircle(cx, nodeCenterY, 10f * density * haloScale, haloPaint)
            if (filled) {
                canvas.drawCircle(cx, nodeCenterY, 6f * density, nodePaint)
            } else {
                canvas.drawCircle(cx, nodeCenterY, 5f * density, ringPaint)
            }
        }
    }
