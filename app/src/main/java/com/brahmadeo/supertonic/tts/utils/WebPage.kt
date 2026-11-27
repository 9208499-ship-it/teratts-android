package com.brahmadeo.supertonic.tts.utils

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A web page read aloud by its link (owner, 11.2026, "как в Audify"): a link shared from the browser or
 * pasted into the text box — the page is fetched, and its article (not the menus, ads, comments) becomes
 * the text to read. The article is the block with the most paragraph text in it, as reader modes find it.
 */
object WebPage {
    class Page(val title: String, val text: String)

    private val URL = Regex("""https?://[^\s<>"«»]+""")
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0 Mobile Safari/537.36"

    /** The link, if [text] is a link (maybe with the page's title before it, as browsers share); else null. */
    fun linkIn(text: String?): String? {
        val t = text?.trim() ?: return null
        val found = URL.findAll(t).toList()
        if (found.size != 1) return null
        // a text with a link somewhere in it is read as it is
        if (t.removeRange(found[0].range).trim().length > 200) return null
        return found[0].value.trimEnd('.', ',', ';', ':', ')', '!', '?')
    }

    /** Fetches [url] (blocking: not on the main thread) and takes its article out. */
    fun load(url: String): Page {
        // plain http is not let through by Android: its https first
        val tries = if (url.startsWith("http://")) listOf("https://" + url.removePrefix("http://"), url) else listOf(url)
        var last: Exception? = null
        for (u in tries) {
            try {
                val doc = Jsoup.connect(u).userAgent(UA).timeout(20_000).followRedirects(true)
                    .maxBodySize(10 * 1024 * 1024).header("Accept-Language", "ru,en;q=0.8").get()
                val page = extract(doc)
                if (page.text.isNotBlank()) return page
                last = IllegalStateException("no text")
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("no text")
    }

    private val JUNK = Regex("comment|share|social|related|promo|advert|banner|sidebar|subscribe|cookie|popup|" +
        "breadcrumb|footer|header|menu|navbar|recommend|teaser|widget|newsletter", RegexOption.IGNORE_CASE)
    private const val BLOCKS = "h1,h2,h3,h4,h5,h6,p,li,blockquote,pre,dt,dd,figcaption"

    fun extract(doc: Document): Page {
        // the article's own heading (og:title and <title> often carry the site's name and words for search engines)
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.length in 3..300 }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.title().trim()
        val body = doc.body() ?: return Page(title, "")
        body.select("script,style,noscript,template,svg,canvas,iframe,form,button,input,select,textarea,nav,header," +
            "footer,aside,menu,dialog,[hidden],[aria-hidden=true],[role=navigation],[role=banner],[role=contentinfo]," +
            "[role=complementary]").remove()
        // blocks named as page furniture, unless they hold real text («article-header» is small, the article is not)
        for (e in body.select("[class],[id]").toList()) {
            if (e.parent() == null) continue
            if (!JUNK.containsMatchIn(e.className() + " " + e.id())) continue
            val text = e.select("p").sumOf { it.text().length }
            if (text < 400) e.remove()
        }
        // the block with the most paragraph text: a paragraph counts to its parent, half to the one above
        val score = HashMap<Element, Double>()
        for (p in body.select("p,pre,blockquote")) {
            val n = p.text().length
            if (n < 25) continue
            p.parent()?.let { score[it] = (score[it] ?: 0.0) + n }
            p.parent()?.parent()?.let { score[it] = (score[it] ?: 0.0) + n / 2.0 }
        }
        val best = score.maxByOrNull { it.value }?.key ?: body
        val root = if (best.select("p").size <= 1 && best.parent() != null) best.parent()!! else best

        val out = StringBuilder()
        val picked = root.select(BLOCKS)
        val set = picked.toHashSet()
        for (e in picked) {
            // a paragraph inside a quote or a list item: taken with it
            if (e.parents().any { it in set }) continue
            val t = e.text().replace(' ', ' ').trim()
            if (t.isEmpty()) continue
            out.append(t).append('\n')
        }
        var text = out.toString().trim()
        // a page made of <div>s and <br>s
        if (text.length < 200) text = root.wholeText().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        // the title on top, if the article does not begin with it
        if (title.isNotEmpty() && !text.startsWith(title.take(40))) text = title + "\n" + text
        return Page(title, text)
    }
}
