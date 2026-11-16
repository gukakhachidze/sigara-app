package ge.sigara.scanner

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class ViewfinderView @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val d = resources.displayMetrics.density
    private val dim = Paint().apply { color = 0x99000000.toInt() }
    private val bracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF10B981.toInt(); style = Paint.Style.STROKE; strokeWidth = 4 * d; strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF8FAFC.toInt(); textSize = 15 * d; textAlign = Paint.Align.CENTER
        setShadowLayer(6 * d, 0f, 0f, 0xFF000000.toInt())
    }
    private val box = RectF()
    private val path = Path()
    private val label = context.getString(R.string.scan_hint)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bw = w * 0.86f; val bh = h * 0.2f
        box.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
        val r = 18 * d; val l = 30 * d
        path.reset()
        path.addRect(0f, 0f, w, h, Path.Direction.CW)
        path.addRoundRect(box, r, r, Path.Direction.CCW)
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, dim)

        val x1 = box.left; val y1 = box.top; val x2 = box.right; val y2 = box.bottom
        val p = Path()
        p.moveTo(x1, y1 + l); p.lineTo(x1, y1 + r); p.arcTo(RectF(x1, y1, x1 + 2 * r, y1 + 2 * r), 180f, 90f); p.lineTo(x1 + l, y1)
        p.moveTo(x2 - l, y1); p.lineTo(x2 - r, y1); p.arcTo(RectF(x2 - 2 * r, y1, x2, y1 + 2 * r), 270f, 90f); p.lineTo(x2, y1 + l)
        p.moveTo(x2, y2 - l); p.lineTo(x2, y2 - r); p.arcTo(RectF(x2 - 2 * r, y2 - 2 * r, x2, y2), 0f, 90f); p.lineTo(x2 - l, y2)
        p.moveTo(x1 + l, y2); p.lineTo(x1 + r, y2); p.arcTo(RectF(x1, y2 - 2 * r, x1 + 2 * r, y2), 90f, 90f); p.lineTo(x1, y2 - l)
        canvas.drawPath(p, bracket)
        canvas.drawText(label, w / 2, y1 - 16 * d, text)
    }
}
