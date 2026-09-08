package com.seamless.player.data.subtitle

import java.util.Locale

/**
 * Turning whatever a file name or a provider says into a language, and back into a word.
 *
 * The platform already knows every language code there is — [Locale.getDisplayLanguage] is
 * the right tool for showing one — so this holds only the part it cannot do: recognising the
 * forms that actually turn up in file names. `Movie.eng.srt`, `Movie.English.srt` and
 * `Movie.en.srt` are all the same claim, and none of the last two is a language tag.
 *
 * The alias table is not exhaustive and does not need to be. Anything missing falls through
 * as its own tag, which still groups and still displays; the table only decides whether two
 * spellings of the same language are recognised as one.
 */
object SubtitleLanguages {

    /**
     * Three-letter codes and English names, mapped onto the two-letter code.
     *
     * Chosen by what appears in subtitle file names in practice rather than by trying to
     * cover ISO 639 — hence Bangla alongside Bengali, and both spellings of the codes that
     * have a bibliographic and a terminological form (ger/deu, fre/fra).
     */
    private val ALIASES: Map<String, String> = buildMap {
        // `alias`, not `put`. A local function called put shadows MutableMap.put inside this
        // builder, so `put(it, code)` resolved to the local function and called itself — an
        // infinite recursion in a static initialiser. The StackOverflowError it threw is an
        // Error rather than an Exception, so it walked past every `catch (Exception)` on the
        // way out, and every later touch of this object threw NoClassDefFoundError instead.
        // Local subtitles silently never appeared and online search reported a generic
        // failure, neither of which pointed anywhere near here.
        fun alias(code: String, vararg names: String) {
            names.forEach { this[it] = code }
        }
        alias("en", "eng", "english")
        alias("bn", "ben", "bengali", "bangla")
        alias("hi", "hin", "hindi")
        alias("ur", "urd", "urdu")
        alias("ta", "tam", "tamil")
        alias("te", "tel", "telugu")
        alias("ml", "mal", "malayalam")
        alias("kn", "kan", "kannada")
        alias("mr", "mar", "marathi")
        alias("ne", "nep", "nepali")
        alias("si", "sin", "sinhala")
        alias("es", "spa", "spanish", "espanol", "español")
        alias("fr", "fre", "fra", "french", "francais", "français")
        alias("de", "ger", "deu", "german", "deutsch")
        alias("it", "ita", "italian", "italiano")
        alias("pt", "por", "portuguese", "portugues", "português")
        alias("ru", "rus", "russian")
        alias("uk", "ukr", "ukrainian")
        alias("pl", "pol", "polish")
        alias("nl", "dut", "nld", "dutch")
        alias("sv", "swe", "swedish")
        alias("no", "nor", "norwegian")
        alias("da", "dan", "danish")
        alias("fi", "fin", "finnish")
        alias("cs", "ces", "cze", "czech")
        alias("hu", "hun", "hungarian")
        alias("ro", "ron", "rum", "romanian")
        alias("el", "ell", "gre", "greek")
        alias("tr", "tur", "turkish")
        alias("ar", "ara", "arabic")
        alias("he", "heb", "hebrew")
        alias("fa", "per", "fas", "persian", "farsi")
        alias("id", "ind", "indonesian")
        alias("ms", "may", "msa", "malay")
        alias("th", "tha", "thai")
        alias("vi", "vie", "vietnamese")
        alias("ja", "jpn", "japanese")
        alias("ko", "kor", "korean")
        alias("zh", "chi", "zho", "chinese")
        alias("tl", "tgl", "tagalog", "filipino")
        alias("sw", "swa", "swahili")
    }

    /**
     * The canonical tag for [value], or null when it is not a language at all.
     *
     * Region is kept where it was given — `en-US` stays `en-US` — because a subtitle labelled
     * for one region is genuinely different from one labelled for another, and throwing that
     * away would silently merge two files that say different things.
     */
    fun normalise(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = raw.lowercase(Locale.ROOT).replace('_', '-')
        ALIASES[lower]?.let { return it }

        val base = lower.substringBefore('-')
        val region = lower.substringAfter('-', "")
        val canonical = ALIASES[base] ?: base.takeIf { it.length == 2 } ?: return null
        if (region.isEmpty()) return canonical
        return canonical + "-" + region.uppercase(Locale.ROOT)
    }

    /**
     * What to put on a row: "English", or "English (US)" where a region was specified.
     *
     * Falls back to the tag itself. A tag we cannot name is still worth showing — it is what
     * the file said, and the user may well recognise it.
     */
    fun displayName(tag: String?): String {
        val normalised = normalise(tag) ?: return tag?.takeIf { it.isNotEmpty() } ?: ""
        val locale = Locale.forLanguageTag(normalised)
        val language = locale.getDisplayLanguage(Locale.getDefault())
            .takeIf { it.isNotEmpty() && !it.equals(normalised, ignoreCase = true) }
            ?: return normalised
        val country = locale.getDisplayCountry(Locale.getDefault())
        return if (country.isEmpty()) language else "$language ($country)"
    }
}
