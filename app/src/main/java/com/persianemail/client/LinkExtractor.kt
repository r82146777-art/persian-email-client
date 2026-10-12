package com.persianemail.client

/**
 * Extract http(s) verification / action links from plain or lightly-HTML email text.
 */
object LinkExtractor {

    data class FoundLink(
        val url: String,
        val label: String
    )

    private val urlRegex = Regex(
        """https?://[^\s<>"'\)\]]+""",
        RegexOption.IGNORE_CASE
    )

    private val hrefRegex = Regex(
        """(?is)<a[^>]+href\s*=\s*["'](https?://[^"']+)["'][^>]*>(.*?)</a>"""
    )

    private val verifyWords = listOf(
        "verify", "confirm", "activation", "activate", "validation", "validate",
        "تأیید", "تایید", "فعال", "کلیک", "click", "confirm email", "verify email",
        "activate account", "تایید ایمیل", "تأیید ایمیل"
    )

    fun extract(subject: String, body: String): List<FoundLink> {
        val found = LinkedHashMap<String, FoundLink>()

        hrefRegex.findAll(body).forEach { m ->
            val url = cleanUrl(m.groupValues[1])
            val labelRaw = m.groupValues[2]
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            val label = if (labelRaw.isNotBlank()) labelRaw else shorten(url)
            if (url.isNotBlank()) {
                found.putIfAbsent(url, FoundLink(url, label))
            }
        }

        val plain = body.replace(Regex("<[^>]+>"), " ")
        urlRegex.findAll("$subject\n$plain").forEach { m ->
            val url = cleanUrl(m.value)
            if (url.isNotBlank()) {
                found.putIfAbsent(url, FoundLink(url, shorten(url)))
            }
        }

        val all = found.values.toList()
        val verify = all.filter { link ->
            val blob = (link.label + " " + link.url).lowercase()
            verifyWords.any { w -> blob.contains(w.lowercase()) }
        }
        return (verify + all.filter { it !in verify }).distinctBy { it.url }.take(12)
    }

    fun linkifyBody(body: String): Pair<String, List<FoundLink>> {
        val links = extract("", body)
        val display = body
            .replace(hrefRegex) { m ->
                val url = cleanUrl(m.groupValues[1])
                val label = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                if (label.isNotBlank()) "$label ( $url ) " else "$url "
            }
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .trim()
        return display to links
    }

    private fun cleanUrl(raw: String): String {
        var u = raw.trim()
        while (u.isNotEmpty() && u.last() in ".,;:!?)]}>'\"") {
            u = u.dropLast(1)
        }
        return u.replace("&amp;", "&")
    }

    private fun shorten(url: String): String {
        val noProto = url.removePrefix("https://").removePrefix("http://")
        return if (noProto.length <= 48) noProto else noProto.take(45) + "…"
    }
}
