package com.brahmadeo.supertonic.tts.utils

import java.util.regex.Pattern

/**
 * Enhanced TextNormalizer with comprehensive rule set
 * Handles currencies, numbers, abbreviations, and more for natural TTS
 */
class TextNormalizer {
    private val currencyNormalizer = CurrencyNormalizer()
    private val russianNumbers = RussianNumberNormalizer()

    data class Rule(val pattern: Pattern, val replacement: (java.util.regex.Matcher) -> String)
    private val rules: List<Rule> = initializeRules()

    // Patterns reused across every normalize() / splitIntoSentences() call.
    // Compiling these inline on the hot path was costing measurable CPU on
    // long texts (Moon+ Reader sends sentence-sized chunks rapidly, and each
    // chunk used to re-parse 30+ regexes). Keep them as immutable fields so
    // every TTS engine instance pays the compile cost exactly once.
    private val smushedSentencePattern: Pattern = Pattern.compile("([a-z])\\.([A-Z])")
    private val smushedWordPattern1: Pattern = Pattern.compile("([a-z])([A-Z])")
    private val smushedWordPattern2: Pattern = Pattern.compile("([A-Z])([A-Z][a-z])")
    private val letterNumberPattern: Pattern = Pattern.compile("([a-zA-Z])(\\d)")
    private val numberPattern: Pattern = Pattern.compile("\\b(\\d+(?:\\.\\d+)?)\\b")

    // Punctuation tweaks (applyPunctuationTweaks) — fixed regexes, cached.
    private val ellipsisUnicodeRegex = Regex("…")
    private val ellipsisCollapseRegex = Regex("\\.\\s*\\.\\s*\\.+")
    private val ellipsisLeadingWsRegex = Regex("\\s+\\.{3,}")
    private val doubleMarkRegex = Regex("(?<![?!])([?!])(?![?!])")
    // Force-space: letter (or combining mark) + one punctuation, NOT followed
    // by another punctuation. The negative lookahead is what keeps "..."
    // and "?!" intact — only single trailing marks like "что," "конец!"
    // get split. Digits aren't included on the left side so "1.5" / "3,14"
    // survive as a single number for the Russian number normaliser.
    //
    // applyPunctuationTweaks decides per-mark whether to actually insert the
    // space based on tightQuestionExclamation / tightCommasAndPeriods —
    // those toggles' "don't touch end-of-chunk spacing" stance is honored
    // here too. The shared regex matches ALL marks; we filter at replace time.
    private val forceSpacePunctRegex = Regex("([\\p{L}\\p{M}])([.,;:!?])(?![.,!?])")
    private val tightCommaSet = setOf(",", ";", ":", ".")
    private val tightQuestionSet = setOf("!", "?")

    // splitIntoSentences — both the split regex and the per-abbreviation
    // protect patterns are stable across calls. Building them once avoids
    // re-compiling 24+ patterns per synthesis.
    private val abbreviations = listOf(
        "Mr.", "Mrs.", "Dr.", "Ms.", "Prof.", "Sr.", "Jr.",
        "etc.", "vs.", "e.g.", "i.e.",
        "Jan.", "Feb.", "Mar.", "Apr.", "May.", "Jun.",
        "Jul.", "Aug.", "Sep.", "Oct.", "Nov.", "Dec.",
        "U.S.", "U.K.", "E.U."
    )
    private val abbreviationPatterns: List<Pattern> = abbreviations.map { abbr ->
        Pattern.compile("\\b" + Pattern.quote(abbr), Pattern.CASE_INSENSITIVE)
    }
    private val sentenceSplitPattern: Pattern =
        Pattern.compile("(?<=[.!?]['\"”’]?)\\s+(?=['\"“‘]?[\\p{L}\\d])|(?<=;)\\s+")
    private val commaSplitPattern: Pattern = Pattern.compile("(?<=,)\\s+")

    private fun initializeRules(): List<Rule> {
        val rulesList = mutableListOf<Rule>()
        
        fun addStr(regex: String, replacement: String) {
            rulesList.add(Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE)) { replacement })
        }
        
        fun addLambda(regex: String, replacement: (java.util.regex.Matcher) -> String) {
            rulesList.add(Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement))
        }

        // QUOTE PUNCTUATION SPACING (Model Stability)
        // Adds space between double quote and punctuation (".) -> (" .) to prevent audio glitches.
        // Toggleable: "tight ?/!" skips this for ?/!, "tight , and ." skips it for period.
        addLambda("([\"”])([.!?])") { m ->
            val punct = m.group(2) ?: ""
            val skip = (punct == "." && PunctuationPrefs.tightCommasAndPeriods) ||
                       ((punct == "?" || punct == "!") && PunctuationPrefs.tightQuestionExclamation)
            if (skip) "${m.group(1)}${m.group(2)}" else "${m.group(1)} ${m.group(2)}"
        }

        // PARENTHESES SPACING (Audio Fix)
        // Adds space inside parentheses to fix tokenization artifacts
        addLambda("\\(([^)]+)\\)") { m ->
            "( ${m.group(1)} )"
        }

        // RANGE NORMALIZATION (e.g. 10-15 years -> 10 to 15 years)
        // Matches digits separated by hyphen (-), en dash (–), or em dash (—)
        addLambda("\\b(\\d+)\\s*[-–—]\\s*(\\d+)\\b") { m ->
            "${m.group(1)} to ${m.group(2)}"
        }

        // EM DASH NORMALIZATION (Priority: High)
        // Replace em dashes with comma to prevent hard pauses/sentence splitting
        addStr("\\s*[—]\\s*", ", ")

        // EMERGENCY NUMBERS (Priority: Highest)
        addStr("\\b911\\b", "nine one one")
        addLambda("\\b(999|112|000)\\b") { m -> 
            val num = m.group(1) ?: ""
            num.toCharArray().joinToString(" ") 
        }

        // MEASUREMENTS
        addLambda("\\b(\\d+(?:\\.\\d+)?)\\s*m\\b(?=[^a-zA-Z]|$)") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            if (amount == "1") "1 meter" else "$valStr meters"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)(km|mi)\\b") { m ->
            val amount = m.group(1) ?: ""
            val unit = m.group(2)?.lowercase() ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            val fullUnit = if (unit == "km") "kilometers" else "miles"
            "$valStr $fullUnit"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)(kph|mph|kmh|km/h|m/s)\\b") { m ->
            val amount = m.group(1) ?: ""
            val unit = m.group(2)?.lowercase() ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            val fullUnit = when (unit) {
                "kph", "kmh", "km/h" -> "kilometers per hour"
                "mph" -> "miles per hour"
                "m/s" -> "meters per second"
                else -> unit
            }
            "$valStr $fullUnit"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)(kg|g|lb|lbs)\\b") { m ->
            val amount = m.group(1) ?: ""
            val unit = m.group(2)?.lowercase() ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            val fullUnit = when (unit) {
                "kg" -> "kilograms"
                "g" -> "grams"
                "lb", "lbs" -> "pounds"
                else -> unit
            }
            if (amount == "1") "1 ${fullUnit.trimEnd('s')}" else "$valStr $fullUnit"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)h\\b") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            if (amount == "1") "1 hour" else "$valStr hours"
        }

        // LARGE NUMBERS (Non-currency)
        addLambda("\\b(\\d+(?:\\.\\d+)?)\\s*(?:M|mn)\\b") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            "$valStr million"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)\\s*(?:B|bn)\\b") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            "$valStr billion"
        }

        addLambda("\\b(\\d+(?:\\.\\d+)?)tn\\b") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            "$valStr trillion"
        }

        // PERCENTAGES
        addLambda("\\b(\\d+(?:\\.\\d+)?)%") { m ->
            val amount = m.group(1) ?: ""
            val valStr = if (amount.contains(".")) amount.replace(".", " point ") else amount
            "$valStr percent"
        }

        // ORDINALS
        addLambda("\\b(\\d+)(st|nd|rd|th)\\b") { m ->
            val num = m.group(1)?.toIntOrNull() ?: 0
            numberToOrdinal(num)
        }

        // YEARS
        // Rule: 2000-2009 (Priority over general split)
        addLambda("\\b200(\\d)\\b") { m ->
            val digit = m.group(1) ?: "0"
            if (digit == "0") "two thousand" else "two thousand $digit"
        }

        // Rule: 1900-1909
        addLambda("\\b190(\\d)\\b") { m ->
            val digit = m.group(1) ?: "0"
            if (digit == "0") "nineteen hundred" else "nineteen oh $digit"
        }

        // YEARS (Split 4 digit years starting with 19 or 20)
        addLambda("\\b(19|20)(\\d{2})\\b(?!s)") { m ->
            "${m.group(1)} ${m.group(2)}"
        }

        // TITLES
        addLambda("\\b(Prof|Dr|Mr|Mrs|Ms)\\.\\s+") { m ->
            when (m.group(1)) {
                "Prof" -> "Professor "
                "Dr" -> "Doctor "
                "Mr" -> "Mister "
                "Mrs" -> "Missus "
                "Ms" -> "Miss "
                else -> m.group(0) ?: ""
            }
        }

        // ABBREVIATIONS
        addLambda("\\b(approx|vs|etc)\\.\\b") { m ->
            when (m.group(1)?.lowercase()) {
                "approx" -> "approximately"
                "vs" -> "versus"
                "etc" -> "et cetera"
                else -> m.group(0) ?: ""
            }
        }

        return rulesList
    }

    /**
     * Punctuation tweaks driven by [PunctuationPrefs]. When all toggles are
     * off this is a no-op — the input text is returned verbatim. Each branch
     * is independent so users can layer them however they want (e.g. tight
     * ellipsis + doubled marks, but legacy spacing for commas).
     */
    private fun applyPunctuationTweaks(text: String): String {
        var t = text

        // Ellipsis: U+2026 → "...", collapse stretched "." or ". ." sequences
        // to a canonical "...", and remove any whitespace immediately before
        // it so the model gets one expressive pause instead of three.
        if (PunctuationPrefs.tightEllipsis) {
            t = ellipsisUnicodeRegex.replace(t, "...")
            t = ellipsisCollapseRegex.replace(t, "...")
            t = ellipsisLeadingWsRegex.replace(t, "...")
        }

        // Doubled question/exclamation marks. Only applies to a single mark —
        // we don't want to turn "!!" into "!!!" and so on. Run after ellipsis
        // normalization so the period collapse can't accidentally consume `?`.
        if (PunctuationPrefs.strengthenIntonation) {
            t = doubleMarkRegex.replace(t, "$1$1")
        }

        // Force a space between a word and trailing punctuation so the model's
        // tokenizer sees a clean word token. Pre-dict in the pipeline means
        // wordPattern in AccentDictionaryManager still finds the same word
        // (it already excludes punctuation), but the final text sent to the
        // engine has cleaner segmentation. Excludes consecutive punctuation
        // (`...`, `?!`) via negative lookahead so the previous tweaks survive.
        //
        // Honors tightCommasAndPeriods and tightQuestionExclamation — if the
        // user explicitly said "don't add spaces around `,;:.`", we leave
        // those marks alone here too. Otherwise the two toggles would
        // contradict each other when both are on.
        if (PunctuationPrefs.forceSpaceBeforePunctuation) {
            val tightCommas = PunctuationPrefs.tightCommasAndPeriods
            val tightQuestion = PunctuationPrefs.tightQuestionExclamation
            t = forceSpacePunctRegex.replace(t) { match ->
                val mark = match.groupValues[2]
                val suppress = (mark in tightCommaSet && tightCommas) ||
                               (mark in tightQuestionSet && tightQuestion)
                if (suppress) match.value
                else "${match.groupValues[1]} ${match.groupValues[2]}"
            }
        }

        return t
    }

    private fun numberToOrdinal(num: Int): String {
        val ordinals = mapOf(
            1 to "first", 2 to "second", 3 to "third", 4 to "fourth", 5 to "fifth",
            6 to "sixth", 7 to "seventh", 8 to "eighth", 9 to "ninth", 10 to "tenth",
            11 to "eleventh", 12 to "twelfth", 13 to "thirteenth", 14 to "fourteenth",
            15 to "fifteenth", 16 to "sixteenth", 17 to "seventeenth", 18 to "eighteenth",
            19 to "nineteenth", 20 to "twentieth"
        )
        
        if (ordinals.containsKey(num)) return ordinals[num]!!
        
        val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        val ones = arrayOf("", "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
        
        if (num < 100) {
            val tenDigit = num / 10
            val oneDigit = num % 10
            if (oneDigit == 0) return "${tens[tenDigit]}th"
            return "${tens[tenDigit]} ${ones[oneDigit]}"
        }
        
        val lastTwo = num % 100
        if (lastTwo in 11..13) return "${num}th"
        
        val lastDigit = num % 10
        return when (lastDigit) {
            1 -> "${num}st"
            2 -> "${num}nd"
            3 -> "${num}rd"
            else -> "${num}th"
        }
    }

    // Footnote marks are not read aloud: "[12]", "{3}", "[4, 5]", superscript "¹²"
    // (but not "м²"), and asterisks glued to a word ("слово*"), never "* * *".
    private val footnoteBracket = Regex("\\s?[\\[{]\\s*\\d{1,4}(?:\\s*[,–-]\\s*\\d{1,4})*\\s*[\\]}]")
    private val footnoteSuperscript = Regex("(?<![мМ])[⁰¹²³⁴⁵⁶⁷⁸⁹]+")
    private val footnoteAsterisk = Regex("(?<=[\\p{L}.,!?»\")])\\*{1,3}(?![\\p{L}*])")

    fun stripFootnoteMarks(text: String): String {
        var t = footnoteBracket.replace(text, "")
        t = footnoteSuperscript.replace(t, "")
        t = footnoteAsterisk.replace(t, "")
        return t
    }

    fun normalize(text: String, lang: String = "en", isAdvancedEnabled: Boolean = false): String {
        val lowerLang = lang.lowercase()

        // Pre-pass for user-controlled punctuation tweaks. Done before Lexicon
        // so any rules the user writes still match against the original text,
        // and *before* number/accent passes so stressed Russian numbers don't
        // get double-`?` artefacts on the second cycle.
        var inputText = applyPunctuationTweaks(stripFootnoteMarks(text))

        // Pipeline for everything except Korean (whose tokenisation does not
        // play nicely with whole-word patches):
        //   1) user lexicon — highest priority
        //   2) Russian number-to-words spelling
        //   3) bulk accent dictionary — applied last so it can stress the
        //      words that the number normaliser just emitted ("две тысячи
        //      двадцать четыре" -> "две ты́сячи два́дцать четы́ре").
        var processedText = if (lowerLang != "ko") {
            var t = LexiconManager.apply(inputText)
            if (lowerLang.startsWith("ru")) {
                t = russianNumbers.normalize(t)
            }
            AccentDictionaryManager.apply(t, lowerLang)
        } else {
            inputText
        }

        // 2. Determine if we should apply English-style normalization rules
        // Currently: Always for English, or if toggle is on for Romance languages
        val isRomance = lowerLang.startsWith("fr") || lowerLang.startsWith("es") || lowerLang.startsWith("pt")
        val shouldNormalize = lowerLang.startsWith("en") || (isRomance && isAdvancedEnabled)

        if (!shouldNormalize) {
            return processedText
        }

        // Step 0: Fix smushed text from webpage layouts (uses class-level
        // cached patterns — see header for why they're not compiled inline).
        var fixedText = smushedSentencePattern.matcher(processedText).replaceAll("$1. $2")
        fixedText = smushedWordPattern1.matcher(fixedText).replaceAll("$1 $2")
        fixedText = smushedWordPattern2.matcher(fixedText).replaceAll("$1 $2")
        fixedText = letterNumberPattern.matcher(fixedText).replaceAll("$1 $2")

        // Step 1: Currency
        var normalized = currencyNormalizer.normalize(fixedText)
        
        // Step 2: Other rules
        for (rule in rules) {
            val matcher = rule.pattern.matcher(normalized)
            val sb = StringBuffer()
            while (matcher.find()) {
                val replacement = rule.replacement(matcher).replace("\\", "\\\\").replace("$", "\\$")
                matcher.appendReplacement(sb, replacement)
            }
            matcher.appendTail(sb)
            normalized = sb.toString()
        }

        // Step 3: Convert remaining numbers to words (CRITICAL for C++ Engine)
        // Matches integers and decimals (e.g. "300000" -> "three hundred thousand")
        val matcher = numberPattern.matcher(normalized)
        val sb = StringBuffer()
        while (matcher.find()) {
            val numStr = matcher.group(1) ?: ""
            try {
                val replacement = if (numStr.contains(".")) {
                    NumberUtils.convertDouble(numStr.toDouble())
                } else {
                    NumberUtils.convert(numStr.toLong())
                }
                matcher.appendReplacement(sb, replacement)
            } catch (_: Exception) {
                // If number is too large for Long, keep it as digits (or implement BigInt logic if needed)
                // For TTS, massive numbers usually read digit-by-digit anyway
                matcher.appendReplacement(sb, numStr)
            }
        }
        matcher.appendTail(sb)
        normalized = sb.toString()

        return normalized
    }

    fun splitIntoSentences(text: String, lang: String = "en"): List<String> {
        // Per-call chunk limit. SMALL gives sub-second first-audio for short
        // notifications; LARGE merges multiple sentences for audiobook
        // narration with continuous intonation; DEFAULT is the legacy 300-char
        // balance. Re-read on every call so the user can flip the toggle
        // between sessions without restarting.
        val chunkLimit = PlaybackPrefs.chunkMode.limit

        var protectedText = text

        // Abbreviation protection — patterns pre-compiled as class fields.
        abbreviationPatterns.forEachIndexed { index, pattern ->
            val placeholder = "__ABBR${index}__"
            protectedText = pattern.matcher(protectedText).replaceAll(placeholder)
        }

        val rawSentences = protectedText.split(sentenceSplitPattern)

        val refinedSentences = mutableListOf<String>()
        val maxLength = chunkLimit

        for (raw in rawSentences) {
            if (raw.length <= maxLength) {
                refinedSentences.add(raw)
            } else {
                // Split long sentences by comma if they are too long
                val subParts = raw.split(commaSplitPattern)
                val currentPart = StringBuilder()
                
                for (part in subParts) {
                    if (currentPart.length + part.length < maxLength) {
                        if (currentPart.isNotEmpty()) currentPart.append(" ")
                        currentPart.append(part)
                    } else {
                        if (currentPart.isNotEmpty()) {
                            // Fix audio cutoff: If breaking at a comma or semi-colon, strip it
                            // to prevent hard stops or artifacts.
                            if (currentPart.endsWith(",") || currentPart.endsWith(";")) {
                                currentPart.deleteCharAt(currentPart.length - 1)
                            }
                            refinedSentences.add(currentPart.toString())
                            currentPart.clear()
                        }
                        currentPart.append(part)
                    }
                }
                if (currentPart.isNotEmpty()) {
                    refinedSentences.add(currentPart.toString())
                }
            }
        }

        val processedSentences = refinedSentences.map { sentence ->
            var restored = sentence
            abbreviations.forEachIndexed { index, abbr ->
                val placeholder = "__ABBR${index}__"
                restored = restored.replace(placeholder, abbr, ignoreCase = true)
            }
            restored.trim()
        }.filter { it.isNotEmpty() }

        // Chunking Logic: Accumulate sentences up to chunkLimit
        // (chunkLimit was computed above from PlaybackPrefs.chunkMode).
        val chunkedSentences = mutableListOf<String>()
        val currentChunk = StringBuilder()

        // Build the volatile-punctuation regex once per call, not once per
        // sentence. Punctuation prefs are stable for the duration of a single
        // synthesis batch — we just need to recompute when the user flips a
        // toggle in the Lexicon screen, which they can only do between calls.
        val isKorean = lang.lowercase().startsWith("ko")
        val volatilePunctuationRegex: Regex? = if (!isKorean) {
            val marks = StringBuilder()
            if (!PunctuationPrefs.tightQuestionExclamation) marks.append("!?")
            if (!PunctuationPrefs.tightCommasAndPeriods) marks.append(",;")
            if (marks.isNotEmpty()) {
                Regex("([${Regex.escape(marks.toString())}])(['\"”’]?)\\s*$")
            } else null
        } else null

        var i = 0
        while (i < processedSentences.size) {
            var sentence = processedSentences[i]

            // Universal Volatile/Punctuation Fix: insert space before
            // configured marks at the end of the sentence to stabilize audio.
            // DISABLED for Korean. The regex (see above) is null when nothing
            // needs rewriting, so this is a single null-check on the fast path.
            if (volatilePunctuationRegex != null) {
                sentence = sentence.replaceFirst(volatilePunctuationRegex, " $1$2")
            }

            // HANDLE STABLE SENTENCE (Standard Accumulation)
            if (currentChunk.length + sentence.length + 1 <= chunkLimit) {
                if (currentChunk.isNotEmpty()) {
                    currentChunk.append(" ")
                }
                currentChunk.append(sentence)
            } else {
                if (currentChunk.isNotEmpty()) {
                    chunkedSentences.add(currentChunk.toString())
                    currentChunk.clear()
                }
                // If a single sentence is huge, add it directly
                if (sentence.length > chunkLimit) {
                    chunkedSentences.add(sentence)
                } else {
                    currentChunk.append(sentence)
                }
            }
            i++
        }

        if (currentChunk.isNotEmpty()) {
            chunkedSentences.add(currentChunk.toString())
        }

        return chunkedSentences
    }
}
