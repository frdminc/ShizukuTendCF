package af.shizuku.manager.home.changelog

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View

/** Renders the only inline formatting a single change carries: `code`, **bold** and [links](url). */
object InlineFormatter {
    private val INLINE = Regex("""`([^`]+)`|\*\*(.+?)\*\*|\[([^]]+)]\(([^)\s]+)\)""")

    fun format(
        text: String,
        chipColor: Int,
        density: Float,
        onLink: (String) -> Unit,
    ): CharSequence {
        val out = SpannableStringBuilder()
        var last = 0
        for (m in INLINE.findAll(text)) {
            out.append(text, last, m.range.first)
            val start = out.length
            when {
                m.groups[1] != null -> {
                    out.append(m.groupValues[1])
                    out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(CodeChipSpan(chipColor, density), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groups[2] != null -> {
                    out.append(m.groupValues[2])
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    out.append(m.groupValues[3])
                    val url = m.groupValues[4]
                    out.setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) = onLink(url)
                        },
                        start,
                        out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return out
    }

    /** Draws a rounded tonal chip behind inline code, with a little horizontal breathing room. */
    private class CodeChipSpan(
        private val color: Int,
        density: Float,
    ) : ReplacementSpan() {
        private val pad = 4f * density
        private val radius = 6f * density

        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int = (paint.measureText(text, start, end) + pad * 2).toInt()

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint,
        ) {
            val width = paint.measureText(text, start, end)
            val fm = paint.fontMetrics
            val rect = RectF(x, y + fm.ascent, x + width + pad * 2, y + fm.descent)
            val old = paint.color
            paint.color = color
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.color = old
            canvas.drawText(text, start, end, x + pad, y.toFloat(), paint)
        }
    }
}
