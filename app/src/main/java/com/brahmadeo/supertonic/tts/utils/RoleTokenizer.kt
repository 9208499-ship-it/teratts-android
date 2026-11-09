package com.brahmadeo.supertonic.tts.utils

/**
 * The tokenizer of the role model (RuModernBERT): byte-level BPE, exactly as the Python one
 * (`tokenizers`, ByteLevel, use_regex) — same ids and the same character offsets, which tie
 * each line and each name in the text to its tokens.
 *
 * [vocab]: token → id; [merges]: pairs in rank order; [added]: added tokens that are not special
 * (runs of spaces, "[unused0]"…), matched in the text before splitting it into words.
 */
class RoleTokenizer(
    private val vocab: Map<String, Int>,
    merges: List<Pair<String, String>>,
    added: Map<String, Int>,
    val cls: Int,
    val sep: Int
) {
    class Encoding(val ids: IntArray, val start: IntArray, val end: IntArray)

    private val ranks = HashMap<String, Int>(merges.size * 2).apply {
        merges.forEachIndexed { i, (a, b) -> put(a + "\u0000" + b, i) }
    }
    private val addedSorted = added.entries.sortedByDescending { it.key.length }
    private val addedFirst = added.keys.map { it[0] }.toSet()
    private val cache = HashMap<String, List<String>>()

    private val byteChar = CharArray(256).also { t ->
        val bs = ArrayList<Int>()
        bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0 until 256) if (b !in bs) { bs += b; cs += 256 + n; n++ }
        for (i in bs.indices) t[bs[i]] = cs[i].toChar()
    }

    private val SPLIT = Regex("""'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""")

    /** [text] → ids with [CLS] … [SEP] and, for each token, its start and end in [text] (0, 0 for the special ones). */
    fun encode(text: String): Encoding {
        val norm = normalize(text)
        val s = norm.first
        val map = norm.second            // index in s → index in text (map[s.length] = text.length)
        val ids = ArrayList<Int>(); val st = ArrayList<Int>(); val en = ArrayList<Int>()
        ids += cls; st += 0; en += 0
        var i = 0
        var segStart = 0
        fun flush(upTo: Int) {
            if (upTo > segStart) words(s, segStart, upTo, map, ids, st, en)
        }
        while (i < s.length) {
            val c = s[i]
            if (c in addedFirst) {
                val hit = addedSorted.firstOrNull { s.startsWith(it.key, i) }
                if (hit != null) {
                    flush(i)
                    ids += hit.value; st += map[i]; en += map[i + hit.key.length]
                    i += hit.key.length
                    segStart = i
                    continue
                }
            }
            i++
        }
        flush(s.length)
        ids += sep; st += 0; en += 0
        return Encoding(ids.toIntArray(), st.toIntArray(), en.toIntArray())
    }

    private fun words(s: String, a: Int, b: Int, map: IntArray, ids: MutableList<Int>, st: MutableList<Int>, en: MutableList<Int>) {
        val seg = s.substring(a, b)
        for (m in SPLIT.findAll(seg)) {
            val w = m.value
            val w0 = a + m.range.first
            // bytes of the piece, and the char each byte belongs to
            val bytes = w.toByteArray(Charsets.UTF_8)
            val charOf = IntArray(bytes.size)
            var bi = 0
            var ci = 0
            while (ci < w.length) {
                val cp = w.codePointAt(ci)
                val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                val cl = Character.charCount(cp)
                for (k in 0 until n) charOf[bi + k] = ci
                bi += n; ci += cl
            }
            val mapped = StringBuilder(bytes.size)
            for (x in bytes) mapped.append(byteChar[x.toInt() and 0xff])
            val pieces = bpe(mapped.toString())
            var p = 0
            for (t in pieces) {
                val id = vocab[t] ?: continue
                val ts = p; val te = p + t.length            // byte range (the leading space is part of it, as in Python)
                val cs = charOf[ts]
                val lastC = charOf[te - 1]
                val ce = lastC + Character.charCount(w.codePointAt(lastC))
                ids += id; st += map[w0 + cs]; en += map[w0 + ce]
                p += t.length
            }
        }
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        var word = token.map { it.toString() }.toMutableList()
        if (word.size > 1) while (true) {
            var best = -1; var bestRank = Int.MAX_VALUE
            for (k in 0 until word.size - 1) {
                val r = ranks[word[k] + "\u0000" + word[k + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = k }
            }
            if (best < 0) break
            val a = word[best]; val b = word[best + 1]
            val out = ArrayList<String>(word.size)
            var k = 0
            while (k < word.size) {
                if (k < word.size - 1 && word[k] == a && word[k + 1] == b) { out += a + b; k += 2 } else { out += word[k]; k++ }
            }
            word = out
            if (word.size == 1) break
        }
        if (cache.size > 50_000) cache.clear()
        cache[token] = word
        return word
    }

    /** NFC, keeping where each character came from (texts are almost always NFC already). */
    private fun normalize(text: String): Pair<String, IntArray> {
        if (java.text.Normalizer.isNormalized(text, java.text.Normalizer.Form.NFC))
            return text to IntArray(text.length + 1) { it }
        val sb = StringBuilder(text.length)
        val map = ArrayList<Int>(text.length + 1)
        var i = 0
        while (i < text.length) {
            var j = i + Character.charCount(text.codePointAt(i))
            while (j < text.length && Character.getType(text.codePointAt(j)) == Character.NON_SPACING_MARK.toInt()) j += Character.charCount(text.codePointAt(j))
            val g = java.text.Normalizer.normalize(text.substring(i, j), java.text.Normalizer.Form.NFC)
            for (k in g.indices) map += i
            sb.append(g)
            i = j
        }
        map += text.length
        // a token ending inside a group ends where the group ends
        val arr = map.toIntArray()
        return sb.toString() to arr
    }
}
