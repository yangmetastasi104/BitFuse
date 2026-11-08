package com.music.bitchord.data.spotify

/**
 * Accepts what the user pastes from browser devtools — either the bare
 * `sp_dc` value, a whole cookie string (`sp_dc=...; other=...`), or a
 * `Cookie:` header line — and returns the bare value, or null when there is
 * no `sp_dc` in it. Mirrors MetroFuse's `normalizeSpotifyCookieInput`.
 */
object SpotifyCookie {
    /**
     * Accepts what the user pastes from browser devtools — either the bare
     * `sp_dc` value, a whole cookie string (`sp_dc=...; sp_t=...; ...`), or a
     * `Cookie:` header line — and returns the bare `sp_dc` value, or null when
     * there is no `sp_dc` in it.
     */
    fun normalize(raw: String?): String? = extract(raw, "sp_dc")

    /**
     * Extracts the `sp_t` personalization/CSRF cookie from the same input the
     * user pastes for `sp_dc`. Returns null when it is absent — callers must
     * treat that as "no personalization", not as an error: Spotify endpoints
     * that need it simply won't be called.
     */
    fun extractSpT(raw: String?): String? = extract(raw, "sp_t")

    /** True when the pasted cookie carries a personalization token. */
    fun hasPersonalization(raw: String?): Boolean = extractSpT(raw) != null

    /** Keeps only cookie name/value pairs, dropping WebView attributes and whitespace. */
    fun normalizeHeader(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val pairs = raw.trim().removePrefix("Cookie:").removePrefix("cookie:")
            .split(';').mapNotNull { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isBlank() || value.isBlank() || name.contains(' ') ||
                    name.equals("Path", ignoreCase = true) ||
                    name.equals("Domain", ignoreCase = true) ||
                    name.equals("Expires", ignoreCase = true) ||
                    name.equals("Max-Age", ignoreCase = true) ||
                    name.equals("Secure", ignoreCase = true) ||
                    name.equals("HttpOnly", ignoreCase = true) ||
                    name.equals("SameSite", ignoreCase = true)
                ) null else "$name=$value"
            }
        return pairs.distinctBy { it.substringBefore('=') }.joinToString(";").takeIf { it.isNotBlank() }
    }

    private fun extract(raw: String?, name: String): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
            .trim(';')
            .trim('"')
        if (text.isBlank()) return null
        // Bare value with no '=': only meaningful for sp_dc (the single value the
        // user is most likely to paste on its own). For sp_t we need a name.
        if (name == "sp_dc" && !text.contains("=") && !text.contains(";") && !text.contains(" ")) {
            return text
        }
        return text.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
