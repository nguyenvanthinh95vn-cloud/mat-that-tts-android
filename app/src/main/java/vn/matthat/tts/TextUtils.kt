package vn.matthat.tts

import java.util.regex.Pattern

/**
 * Port tu tts_engine.py (ban Windows) sang Kotlin.
 * Quy tac ngat nghi mac dinh lay theo config goc cua tool:
 * dau phay 0.25s, dau cham 0.45s, hai cham 0.3s, cham phay 0.4s,
 * ngat doan (chunk) 0.5s, ngat xuong dong 1.0s.
 */
data class PauseRules(
    var breakPause: Float = 1.0f,
    var chunkPause: Float = 0.5f,
    var comma: Float = 0.25f,
    var dot: Float = 0.45f,
    var colon: Float = 0.30f,
    var semi: Float = 0.40f
) {
    /** Tra ve so giay nghi sau ky tu nay, null neu khong phai dau cau. */
    fun pauseFor(ch: Char): Float? = when (ch) {
        ',', '，' -> comma
        '.', '。', '!', '?', '！', '？' -> dot
        ':', '：' -> colon
        ';', '；' -> semi
        else -> null
    }
}

data class SrtCue(val start: Double, val end: Double, val text: String)

fun cleanText(text: String): String {
    var t = text.replace(Regex("<[^>]+>"), "")
    t = t.replace(Regex("\\{[^}]*\\}"), "")
    t = t.replace("\r\n", "\n").replace("\r", "\n")
    t = t.replace(Regex("[ \\t]+"), " ")
    t = t.replace(Regex("\\n{3,}"), "\n\n")
    return t.trim()
}

private val SENTENCE_SPLIT = Regex("(?<=[.!?…。！？])\\s+")

fun splitSentences(text: String): List<String> {
    return SENTENCE_SPLIT.split(text).map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Chia text dai thanh cac doan <= maxChars, cat o ranh gioi cau.
 * Giong het split_text_into_chunks() cua ban Windows.
 */
fun splitIntoChunks(text: String, maxChars: Int = 800): List<String> {
    val cleaned = cleanText(text)
    if (cleaned.isEmpty()) return emptyList()
    val paras = cleaned.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    val chunks = mutableListOf<String>()
    for (para in paras) {
        val sentences = splitSentences(para.replace("\n", " "))
        var buf = ""
        for (s in sentences) {
            if (buf.length + s.length + 1 > maxChars && buf.isNotEmpty()) {
                chunks.add(buf.trim())
                buf = s
            } else {
                buf = if (buf.isEmpty()) s else "$buf $s"
            }
            while (buf.length > maxChars) {
                var cut = buf.lastIndexOf(',', maxChars - 1)
                if (cut < maxChars / 2) cut = buf.lastIndexOf(' ', maxChars - 1)
                if (cut <= 0) cut = maxChars
                chunks.add(buf.substring(0, cut).trim())
                buf = buf.substring(cut).trim()
            }
        }
        if (buf.isNotEmpty()) chunks.add(buf.trim())
    }
    return chunks.filter { it.isNotEmpty() }
}

/**
 * Tach text thanh (doan, giay nghi sau doan) theo dau cau.
 * Giong het split_fragments() cua ban Windows.
 */
fun splitFragments(text: String, rules: PauseRules): List<Pair<String, Float>> {
    val frags = mutableListOf<Pair<String, Float>>()
    val buf = StringBuilder()
    for (ch in text) {
        buf.append(ch)
        val pause = rules.pauseFor(ch)
        if (pause != null) {
            if (buf.toString().trim().isNotEmpty()) {
                frags.add(buf.toString().trim() to pause)
            }
            buf.clear()
        }
    }
    if (buf.toString().trim().isNotEmpty()) {
        frags.add(buf.toString().trim() to 0f)
    }
    return frags
}

private val SRT_TS = Pattern.compile(
    "(\\d+):(\\d+):(\\d+)[,.](\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+)[,.](\\d+)"
)

/** Doc noi dung file .srt -> list cue (start_s, end_s, text). Giong parse_srt() ban Windows. */
fun parseSrt(raw: String): List<SrtCue> {
    val norm = raw.replace("\r\n", "\n").replace("\r", "\n")
    val blocks = norm.trim().split(Regex("\\n\\s*\\n"))
    val cues = mutableListOf<SrtCue>()
    fun toS(h: String, m: String, s: String, ms: String): Double =
        h.toInt() * 3600 + m.toInt() * 60 + s.toInt() + ms.toInt() / 1000.0

    for (b in blocks) {
        val lines = b.trim().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) continue
        var m: java.util.regex.Matcher? = null
        var textLines: List<String> = emptyList()
        for (i in lines.indices) {
            val mm = SRT_TS.matcher(lines[i])
            if (mm.find()) {
                m = mm
                textLines = lines.subList(i + 1, lines.size)
                break
            }
        }
        if (m == null) continue
        val start = toS(m.group(1)!!, m.group(2)!!, m.group(3)!!, m.group(4)!!)
        val end = toS(m.group(5)!!, m.group(6)!!, m.group(7)!!, m.group(8)!!)
        val text = cleanText(textLines.joinToString(" "))
        if (end > start && text.isNotEmpty()) {
            cues.add(SrtCue(start, end, text))
        }
    }
    cues.sortBy { it.start }
    return cues
}

/** Ten file an toan: bo ky tu cam trong ten file. */
fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    return if (cleaned.isEmpty()) "" else cleaned
}
