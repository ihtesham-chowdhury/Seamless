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
        fun put(code: String, vararg names: String) {
            names.forEach { put(it, code) }
        }
        put("en", "eng", "english")
        put("bn", "ben", "bengali", "bangla")
        put("hi", "hin", "hindi")
        put("ur", "urd", "urdu")
        put("ta", "tam", "tamil")
        put("te", "tel", "telugu")
        put("ml", "mal", "malayalam")
        put("kn", "kan", "kannada")
        put("mr", "mar", "marathi")
        put("ne", "nep", "nepali")
        put("si", "sin", "sinhala")
        put("es", "spa", "spanish", "espanol", "español")
        put("fr", "fre", "fra", "french", "francais", "français")
        put("de", "ger", "deu", "german", "deutsch")
        put("it", "ita", "italian", "italiano")
        put("pt", "por", "portuguese", "portugues", "português")
        put("ru", "rus", "russian")
        put("uk", "ukr", "ukrainian")
        put("pl", "pol", "polish")
        put("nl", "dut", "nld", "dutch")
        put("sv", "swe", "swedish")
        put("no", "nor", "norwegian")
        put("da", "dan", "danish")
        put("fi", "fin", "finnish")
        put("cs", "ces", "cze", "czech")
        put("hu", "hun", "hungarian")
        put("ro", "ron", "rum", "romanian")
        put("el", "ell", "gre", "greek")
        put("tr", "tur", "turkish")
        put("ar", "ara", "arabic")
        put("he", "heb", "hebrew")
        put("fa", "per", "fas", "persian", "farsi")
        put("id", "ind", "indonesian")
        put("ms", "may", "msa", "malay")
        put("th", "tha", "thai")
        put("vi", "vie", "vietnamese")
        put("ja", "jpn", "japanese")
        put("ko", "kor", "korean")
        put("zh", "chi", "zho", "chinese")
        put("tl", "tgl", "tagalog", "filipino")
        put("sw", "swa", "swahili")
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
