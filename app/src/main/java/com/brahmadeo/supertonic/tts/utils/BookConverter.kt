package com.brahmadeo.supertonic.tts.utils

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Converts FB2 (plain or inside .zip), FB3 and TXT books into a minimal
 * EPUB 3 so the existing Readium-based reader can open them unchanged.
 *
 * Pure JVM: DOM XML + java.util.zip, no Android classes.
 */
object BookConverter {

    class Chapter(var title: String, val paragraphs: MutableList<String> = mutableListOf()) {
        val textLength: Int get() = paragraphs.sumBy { it.length }
    }

    class Book(val title: String, val author: String, val chapters: List<Chapter>)

    /** Lower-case format tag by file name / content, or null if not convertible. */
    fun detectFormat(fileName: String, header: ByteArray): String? {
        val name = fileName.lowercase()
        val head = String(header, Charsets.ISO_8859_1)
        return when {
            name.endsWith(".fb2") -> "fb2"
            name.endsWith(".fb2.zip") -> "fb2zip"
            name.endsWith(".fb3") -> "fb3"
            name.endsWith(".txt") -> "txt"
            name.endsWith(".zip") -> "fb2zip" // checked for an .fb2 member later
            head.contains("<FictionBook") -> "fb2"
            else -> null
        }
    }

    /**
     * A ZIP whose file names may be in CP866 (fb2.zip from Russian libraries, made on
     * Windows): Java reads names as UTF-8 and fails on such an archive.
     */
    fun openZip(file: File): ZipFile {
        try {
            val z = ZipFile(file)
            try { z.entries().asSequence().forEach { it.name }; return z } catch (e: Exception) { z.close(); throw e }
        } catch (e: Exception) {
            return ZipFile(file, Charset.forName("CP866"))
        }
    }

    /** Names of the files in a ZIP (any name encoding). */
    fun zipEntryNames(file: File): List<String> = openZip(file).use { z -> z.entries().asSequence().map { it.name }.toList() }

    /**
     * Convert [source] into [target] (.epub). [title]: the book's own file name, for a book
     * that does not name itself (TXT, an FB2 without <book-title>). Throws on failure.
     */
    fun convert(source: File, format: String, target: File, title: String? = null) {
        val parsed = when (format) {
            "fb2" -> parseFb2(source.readBytes(), source.nameWithoutExtension)
            "fb2zip" -> {
                openZip(source).use { zip ->
                    val entry = zip.entries().asSequence().firstOrNull { it.name.lowercase().endsWith(".fb2") }
                        ?: throw IllegalArgumentException("no .fb2 inside the archive")
                    parseFb2(zip.getInputStream(entry).readBytes(), File(entry.name).nameWithoutExtension)
                }
            }
            "fb3" -> parseFb3(source)
            "txt" -> parseTxt(source)
            else -> throw IllegalArgumentException("unsupported format $format")
        }
        // the imported copy is called book_<hash>: that is no title for the library
        val unnamed = parsed.title.isBlank() || parsed.title == source.nameWithoutExtension || parsed.title.startsWith("book_")
        val book = if (unnamed && !title.isNullOrBlank()) Book(title, parsed.author, parsed.chapters) else parsed
        writeEpub(postprocess(book), target)
    }

    // ------------------------------------------------------------------ FB2

    private fun parseXml(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isValidating = false
        try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (e: Exception) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (e: Exception) {}
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        return doc.documentElement
    }

    private fun localName(n: Node): String = n.nodeName.substringAfter(':')

    private fun children(e: Element): List<Element> {
        val out = ArrayList<Element>()
        val list = e.childNodes
        for (i in 0 until list.length) {
            val n = list.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) out.add(n as Element)
        }
        return out
    }

    private fun firstDescendant(e: Element, vararg path: String): Element? {
        var cur: Element = e
        for (name in path) {
            cur = children(cur).firstOrNull { localName(it) == name } ?: return null
        }
        return cur
    }

    /** Text without footnote links and <sup>. */
    private fun inlineText(e: Element): String {
        val sb = StringBuilder()
        val list = e.childNodes
        for (i in 0 until list.length) {
            val n = list.item(i)
            when (n.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(n.nodeValue)
                Node.ELEMENT_NODE -> {
                    val el = n as Element
                    val name = localName(el)
                    val isNote = name == "a" && (el.getAttribute("type").contains("note"))
                    if (!isNote && name != "sup") sb.append(inlineText(el))
                }
            }
        }
        return cleanWs(sb.toString())
    }

    private fun paragraphsOf(e: Element, out: MutableList<String>) {
        for (ch in children(e)) {
            when (localName(ch)) {
                "p", "v", "subtitle", "text-author" -> inlineText(ch).takeIf { it.isNotEmpty() }?.let { out.add(it) }
                "poem", "stanza", "cite", "epigraph", "table", "tr", "td", "th" -> paragraphsOf(ch, out)
                else -> {}
            }
        }
    }

    private fun titleOf(e: Element): String {
        val parts = ArrayList<String>()
        paragraphsOf(e, parts)
        if (parts.isEmpty()) parts.add(inlineText(e))
        return parts.filter { it.isNotEmpty() }.joinToString(". ")
    }

    private fun walkSection(sec: Element, chapters: MutableList<Chapter>) {
        var title = ""
        var cur: Chapter? = null
        for (ch in children(sec)) {
            when (localName(ch)) {
                "title" -> title = titleOf(ch)
                "section" -> {
                    if (cur == null && title.isNotEmpty()) {
                        chapters.add(Chapter(title)) // part heading; merged into the next chapter
                        title = ""
                    }
                    cur = null
                    walkSection(ch, chapters)
                }
                "image", "annotation" -> {}
                else -> {
                    val paras = ArrayList<String>()
                    when (localName(ch)) {
                        "p", "subtitle", "text-author", "v" -> inlineText(ch).takeIf { it.isNotEmpty() }?.let { paras.add(it) }
                        else -> paragraphsOf(ch, paras)
                    }
                    if (paras.isNotEmpty()) {
                        if (cur == null) {
                            cur = Chapter(title)
                            title = ""
                            chapters.add(cur)
                        }
                        cur.paragraphs.addAll(paras)
                    }
                }
            }
        }
        if (cur == null && title.isNotEmpty()) chapters.add(Chapter(title))
    }

    fun parseFb2(bytes: ByteArray, fallbackTitle: String): Book {
        val root = parseXml(bytes)
        var title = fallbackTitle
        var author = ""
        firstDescendant(root, "description", "title-info")?.let { info ->
            firstDescendant(info, "book-title")?.let { inlineText(it).takeIf { t -> t.isNotEmpty() }?.let { t -> title = t } }
            firstDescendant(info, "author")?.let { a ->
                val first = firstDescendant(a, "first-name")?.let { inlineText(it) } ?: ""
                val last = firstDescendant(a, "last-name")?.let { inlineText(it) } ?: ""
                author = cleanWs("$first $last")
            }
        }
        val chapters = ArrayList<Chapter>()
        val body = children(root).firstOrNull { localName(it) == "body" && it.getAttribute("name").lowercase() !in setOf("notes", "comments", "footnotes") }
            ?: throw IllegalArgumentException("FB2 without <body>")
        walkSection(body, chapters)
        return Book(title, author, chapters)
    }

    // ------------------------------------------------------------------ FB3

    fun parseFb3(file: File): Book {
        openZip(file).use { zip ->
            val bodyEntry = zip.entries().asSequence().firstOrNull { it.name.lowercase().endsWith("body.xml") }
                ?: throw IllegalArgumentException("FB3 without body.xml")
            val descEntry = zip.entries().asSequence().firstOrNull { it.name.lowercase().endsWith("description.xml") }
            var title = file.nameWithoutExtension
            var author = ""
            if (descEntry != null) {
                try {
                    val d = parseXml(zip.getInputStream(descEntry).readBytes())
                    firstDescendant(d, "title", "main")?.let { inlineText(it).takeIf { t -> t.isNotEmpty() }?.let { t -> title = t } }
                    firstDescendant(d, "fb3-relations", "subject")?.let { s ->
                        val first = firstDescendant(s, "first-name")?.let { inlineText(it) } ?: ""
                        val last = firstDescendant(s, "last-name")?.let { inlineText(it) } ?: ""
                        author = cleanWs("$first $last")
                    }
                } catch (e: Exception) {}
            }
            val body = parseXml(zip.getInputStream(bodyEntry).readBytes())
            val chapters = ArrayList<Chapter>()
            walkSection(body, chapters)
            return Book(title, author, chapters)
        }
    }

    // ------------------------------------------------------------------ TXT

    private val txtHeading = Regex("^(глава|часть|книга|пролог|эпилог|chapter|part|prologue|epilogue)\\b.{0,70}$", RegexOption.IGNORE_CASE)

    fun decodeText(bytes: ByteArray): String {
        for (name in listOf("UTF-8", "windows-1251")) {
            try {
                val dec = Charset.forName(name).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                return dec.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            } catch (e: Exception) {}
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun parseTxt(file: File): Book {
        val text = decodeText(file.readBytes()).replace("\r\n", "\n").replace('\r', '\n')
        var blocks = text.split(Regex("\\n\\s*\\n"))
        if (blocks.size < 10) blocks = text.split("\n")
        val chapters = ArrayList<Chapter>()
        var cur: Chapter? = null
        for (b in blocks) {
            val t = cleanWs(b)
            if (t.isEmpty()) continue
            if (txtHeading.matches(t)) {
                cur = Chapter(t)
                chapters.add(cur)
                continue
            }
            if (cur == null) {
                cur = Chapter("")
                chapters.add(cur)
            }
            cur.paragraphs.add(t)
        }
        return Book(file.nameWithoutExtension, "", chapters)
    }

    // ------------------------------------------------------------ structure

    private const val MAX_CHAPTER_CHARS = 40_000

    fun postprocess(book: Book): Book {
        // merge empty / tiny chapters into the following one
        val merged = ArrayList<Chapter>()
        var carryTitle = ""
        val carryParas = ArrayList<String>()
        val src = book.chapters
        for ((i, c) in src.withIndex()) {
            if (c.textLength < 300 && i != src.lastIndex) {
                if (c.paragraphs.isEmpty()) {
                    carryTitle = joinTitles(carryTitle, c.title)
                } else {
                    if (c.title.isNotEmpty()) carryParas.add(c.title)
                    carryParas.addAll(c.paragraphs)
                }
                continue
            }
            val ch = Chapter(joinTitles(carryTitle, c.title))
            ch.paragraphs.addAll(carryParas)
            ch.paragraphs.addAll(c.paragraphs)
            merged.add(ch)
            carryTitle = ""
            carryParas.clear()
        }
        if (carryTitle.isNotEmpty() || carryParas.isNotEmpty()) {
            if (merged.isEmpty()) {
                val ch = Chapter(carryTitle); ch.paragraphs.addAll(carryParas); merged.add(ch)
            } else {
                if (carryTitle.isNotEmpty()) merged.last().paragraphs.add(carryTitle)
                merged.last().paragraphs.addAll(carryParas)
            }
        }
        // split very long chapters
        val out = ArrayList<Chapter>()
        for (c in merged) {
            if (c.textLength <= MAX_CHAPTER_CHARS) { out.add(c); continue }
            var part = Chapter(c.title); var size = 0; var n = 1
            for (p in c.paragraphs) {
                if (size + p.length > MAX_CHAPTER_CHARS && part.paragraphs.isNotEmpty()) {
                    out.add(part); n++
                    part = Chapter(if (c.title.isEmpty()) "" else "${c.title} ($n)"); size = 0
                }
                part.paragraphs.add(p); size += p.length
            }
            out.add(part)
        }
        if (out.isEmpty()) throw IllegalArgumentException("no text found in the book")
        // untitled chapters get a number
        out.forEachIndexed { i, c -> if (c.title.isEmpty()) c.title = "${i + 1}" }
        return Book(book.title, book.author, out)
    }

    private fun joinTitles(a: String, b: String): String =
        if (a.isEmpty()) b else if (b.isEmpty()) a else "${a.trimEnd('.')}. $b"

    private fun cleanWs(s: String): String =
        s.replace('\u00A0', ' ').replace("\u00AD", "").replace(Regex("\\s+"), " ").trim()

    // ---------------------------------------------------------------- EPUB

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun writeEpub(book: Book, target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".part")
        ZipOutputStream(FileOutputStream(tmp)).use { zip ->
            // mimetype must be first and stored uncompressed
            val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val e = ZipEntry("mimetype")
            e.method = ZipEntry.STORED
            e.size = mime.size.toLong()
            e.compressedSize = mime.size.toLong()
            e.crc = CRC32().also { it.update(mime) }.value
            zip.putNextEntry(e); zip.write(mime); zip.closeEntry()

            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }

            put("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
""")
            val ids = book.chapters.indices.map { "ch%03d".format(it + 1) }
            for ((i, c) in book.chapters.withIndex()) {
                val sb = StringBuilder()
                sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>")
                    .append(esc(c.title)).append("</title></head><body>\n")
                if (c.title.isNotEmpty()) sb.append("<h2>").append(esc(c.title)).append("</h2>\n")
                for (p in c.paragraphs) sb.append("<p>").append(esc(p)).append("</p>\n")
                sb.append("</body></html>\n")
                put("OEBPS/${ids[i]}.xhtml", sb.toString())
            }
            val nav = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>")
                .append(esc(book.title)).append("</title></head><body><nav epub:type=\"toc\"><ol>\n")
            for ((i, c) in book.chapters.withIndex()) nav.append("<li><a href=\"${ids[i]}.xhtml\">").append(esc(c.title)).append("</a></li>\n")
            nav.append("</ol></nav></body></html>\n")
            put("OEBPS/nav.xhtml", nav.toString())

            val ncx = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><head><meta name=\"dtb:uid\" content=\"teratts-book\"/></head><docTitle><text>")
                .append(esc(book.title)).append("</text></docTitle><navMap>\n")
            for ((i, c) in book.chapters.withIndex())
                ncx.append("<navPoint id=\"np${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>").append(esc(c.title))
                    .append("</text></navLabel><content src=\"${ids[i]}.xhtml\"/></navPoint>\n")
            ncx.append("</navMap></ncx>\n")
            put("OEBPS/toc.ncx", ncx.toString())

            val opf = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">\n<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
                .append("<dc:identifier id=\"uid\">urn:teratts:").append(System.currentTimeMillis()).append("</dc:identifier>\n")
                .append("<dc:title>").append(esc(book.title)).append("</dc:title>\n")
                .append("<dc:language>ru</dc:language>\n")
            if (book.author.isNotEmpty()) opf.append("<dc:creator>").append(esc(book.author)).append("</dc:creator>\n")
            opf.append("<meta property=\"dcterms:modified\">2026-01-01T00:00:00Z</meta>\n</metadata>\n<manifest>\n")
                .append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
                .append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
            for (id in ids) opf.append("<item id=\"$id\" href=\"$id.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
            opf.append("</manifest>\n<spine toc=\"ncx\">\n")
            for (id in ids) opf.append("<itemref idref=\"$id\"/>\n")
            opf.append("</spine>\n</package>\n")
            put("OEBPS/content.opf", opf.toString())
        }
        if (!tmp.renameTo(target)) throw java.io.IOException("rename failed: $tmp")
    }
}
