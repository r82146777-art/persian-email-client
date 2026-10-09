package com.persianemail.client

/**
 * Extract verification / OTP codes from email subject+body.
 */
object OtpExtractor {
    private val patterns = listOf(
        Regex("""(?i)(?:code|otp|pin|password|verification|verify|کد|رمز|تأیید|تایید|ورود)\D{0,20}(\d{4,8})"""),
        Regex("""(?<![\d])(\d{6})(?![\d])"""),
        Regex("""(?<![\d])(\d{4,5})(?![\d])""")
    )

    fun extract(subject: String, body: String): List<String> {
        val text = "$subject\n$body"
        val found = LinkedHashSet<String>()
        for (p in patterns) {
            p.findAll(text).forEach { m ->
                val code = m.groupValues.lastOrNull { it.isNotBlank() } ?: return@forEach
                // skip years / obvious non-codes
                if (code.length == 4 && code.startsWith("20")) return@forEach
                found.add(code)
            }
        }
        return found.toList().take(5)
    }
}
