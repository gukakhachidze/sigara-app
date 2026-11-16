package ge.sigara.scanner

import com.google.mlkit.vision.text.Text

object PhoneExtractor {
    private const val S = """[\s\-.()]"""
    // 5XX XX XX XX, optionally prefixed by +995 / 995
    private val mobile = Regex("""(?<!\d)(?:\+?\s?995$S*)?(5(?:$S?\d){8})(?!\d)""")
    // Tbilisi landline: 032 2XX XX XX, (032) 2XX XX XX, +995 32 2XX XX XX
    private val landline = Regex("""(?<!\d)(?:\+?\s?995$S*32|\(?\s?0?32\s?\)?)(?:$S*2)(?:$S?\d){6}(?!\d)""")
    private val ocrO = Regex("""(?<=\d)[Oo]|[Oo](?=\d)""")

    data class Hit(val number: String, val cx: Float, val cy: Float)

    fun fixOcr(s: String): String {
        var t = s
        repeat(3) { t = ocrO.replace(t, "0") }
        return t
    }

    /** Normalized numbers (+995...) found in one line, left-to-right, no overlaps. */
    fun findInLine(raw: String): List<String> {
        val line = fixOcr(raw)
        val found = ArrayList<Triple<Int, Int, String>>()
        landline.findAll(line).forEach { m ->
            val dg = m.value.filter { it.isDigit() }
            found += Triple(m.range.first, m.range.last, "+99532" + dg.takeLast(7))
        }
        mobile.findAll(line).forEach { m ->
            val dg = m.value.filter { it.isDigit() }
            found += Triple(m.range.first, m.range.last, "+995" + dg.takeLast(9))
        }
        found.sortBy { it.first }
        val out = ArrayList<String>(); var end = -1
        for ((s, e, n) in found) { if (s > end) { out += n; end = e } }
        return out
    }

    /** Number inside the centre target zone (top-most first), else the one closest to centre. */
    fun pick(text: Text, imgW: Int, imgH: Int): String? {
        val hits = ArrayList<Hit>()
        for (b in text.textBlocks) for (l in b.lines) {
            val box = l.boundingBox
            val cx = if (box != null) box.exactCenterX() / imgW else 0.5f
            val cy = if (box != null) box.exactCenterY() / imgH else 0.5f
            findInLine(l.text).forEach { hits += Hit(it, cx, cy) }
        }
        if (hits.isEmpty()) return null
        val inside = hits.filter { it.cx in 0.05f..0.95f && it.cy in 0.30f..0.70f }
        if (inside.isNotEmpty()) return inside.minByOrNull { it.cy }!!.number
        return hits.minByOrNull { (it.cx - .5f) * (it.cx - .5f) + (it.cy - .5f) * (it.cy - .5f) }!!.number
    }
}
