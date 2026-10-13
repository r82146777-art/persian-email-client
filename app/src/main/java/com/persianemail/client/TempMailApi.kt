package com.persianemail.client

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Temp / Atomic-style disposable email.
 * Primary: mail.tm (persistent account + token refresh).
 * Fallback: Guerrilla Mail.
 */
object TempMailApi {
    private const val TAG = "TempMailApi"
    private val MAILTM_BASES = listOf("https://api.mail.tm", "https://api.mail.gw")

    data class TempAccount(
        val address: String,
        val password: String = "",
        val token: String = "",
        val apiBase: String = "",
        val provider: String = "mailtm" // mailtm | guerrilla
    )

    data class TempMessage(
        val id: String,
        val from: String,
        val subject: String,
        val intro: String,
        val createdAt: String,
        val body: String = "",
        val codes: List<String> = emptyList()
    )

    fun listAvailableDomains(): List<String> {
        val out = LinkedHashSet<String>()
        for (base in MAILTM_BASES) {
            try {
                out.addAll(fetchDomains(base))
            } catch (_: Exception) {
            }
        }
        if (out.isEmpty()) {
            out.add("maxxspace.com")
        }
        return out.toList()
    }

    fun createRandomAccount(): TempAccount {
        val errors = ArrayList<String>()
        try {
            return createMailTm(null, null)
        } catch (e: Exception) {
            errors.add(e.message ?: "mail.tm")
        }
        try {
            return createGuerrilla(null)
        } catch (e: Exception) {
            errors.add(e.message ?: "guerrilla")
        }
        throw IllegalStateException("ساخت ایمیل ممکن نشد:\n" + errors.joinToString("\n"))
    }

    fun createWithUsername(usernameRaw: String, domainPreferred: String? = null): TempAccount {
        val user = usernameRaw.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]"), "")
            .trim('.', '_')
        if (user.length < 3) throw IllegalStateException("نام کاربری حداقل ۳ حرف")
        if (user.length > 30) throw IllegalStateException("نام کاربری حداکثر ۳۰ حرف")
        val errors = ArrayList<String>()
        try {
            return createMailTm(user, domainPreferred)
        } catch (e: Exception) {
            errors.add("Mail.tm: ${e.message}")
        }
        try {
            return createGuerrilla(user)
        } catch (e: Exception) {
            errors.add("Guerrilla: ${e.message}")
        }
        throw IllegalStateException("با این نام ساخته نشد:\n" + errors.joinToString("\n"))
    }

    /** Refresh token if needed, then list messages. */
    fun listMessages(account: TempAccount): Pair<TempAccount, List<TempMessage>> {
        val acc = ensureToken(account)
        val msgs = when (acc.provider) {
            "guerrilla" -> listGuerrilla(acc.token)
            else -> listMailTm(acc.token, acc.apiBase.ifBlank { "https://api.mail.tm" })
        }
        return acc to msgs
    }

    fun getMessage(account: TempAccount, id: String): TempMessage {
        val acc = ensureToken(account)
        return when (acc.provider) {
            "guerrilla" -> getGuerrilla(acc.token, id)
            else -> getMailTm(acc.token, id, acc.apiBase.ifBlank { "https://api.mail.tm" })
        }
    }

    private fun ensureToken(account: TempAccount): TempAccount {
        if (account.provider == "guerrilla") return account
        if (account.token.isNotBlank()) {
            // probe
            try {
                listMailTm(account.token, account.apiBase.ifBlank { "https://api.mail.tm" })
                return account
            } catch (_: Exception) {
                // re-login
            }
        }
        if (account.password.isBlank() || account.address.isBlank()) return account
        val base = account.apiBase.ifBlank { "https://api.mail.tm" }
        val token = loginMailTm(account.address, account.password, base)
        return account.copy(token = token, apiBase = base, provider = "mailtm")
    }

    private fun createMailTm(user: String?, domainPreferred: String?): TempAccount {
        var last: Exception? = null
        for (base in MAILTM_BASES) {
            try {
                val domains = fetchDomains(base)
                if (domains.isEmpty()) throw IllegalStateException("دامنه‌ای نیست")
                val domain = when {
                    !domainPreferred.isNullOrBlank() &&
                        domains.any { it.equals(domainPreferred, true) } -> domainPreferred!!
                    else -> domains.random()
                }
                val local = if (!user.isNullOrBlank()) {
                    user
                } else {
                    "fa" + UUID.randomUUID().toString().replace("-", "").take(10)
                }
                var address = "$local@$domain".lowercase()
                val password = "Aa1" + UUID.randomUUID().toString().replace("-", "")
                try {
                    httpJson(
                        "POST",
                        "$base/accounts",
                        JSONObject().put("address", address).put("password", password).toString()
                    )
                } catch (e: Exception) {
                    // username taken → suffix
                    address = local + UUID.randomUUID().toString().replace("-", "").take(4) + "@" + domain
                    address = address.lowercase()
                    httpJson(
                        "POST",
                        "$base/accounts",
                        JSONObject().put("address", address).put("password", password).toString()
                    )
                }
                val token = loginMailTm(address, password, base)
                return TempAccount(address, password, token, base, "mailtm")
            } catch (e: Exception) {
                Log.e(TAG, "createMailTm $base", e)
                last = e
            }
        }
        throw last ?: IllegalStateException("mail.tm در دسترس نیست")
    }

    private fun loginMailTm(address: String, password: String, base: String): String {
        val res = httpJson(
            "POST",
            "$base/token",
            JSONObject().put("address", address.lowercase()).put("password", password).toString()
        )
        val token = JSONObject(res).optString("token")
        if (token.isBlank()) throw IllegalStateException("ورود به صندوق ناموفق")
        return token
    }

    private fun fetchDomains(base: String): List<String> {
        val json = httpJson("GET", "$base/domains", null)
        val arr = members(json)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val d = item.optString("domain")
            if (d.isNotBlank() && item.optBoolean("isActive", true)) out.add(d)
        }
        return out
    }

    private fun listMailTm(token: String, base: String): List<TempMessage> {
        val json = httpJson("GET", "$base/messages?page=1", null, token)
        val arr = members(json)
        val out = ArrayList<TempMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val from = o.optJSONObject("from")?.optString("address")
                ?: o.optString("from")
            val subject = o.optString("subject", "(بدون موضوع)")
            val intro = o.optString("intro", "")
            val id = o.optString("id").ifBlank {
                o.optString("@id").substringAfterLast("/")
            }
            val created = o.optString("createdAt", "")
            if (id.isBlank()) continue
            out.add(
                TempMessage(
                    id, from, subject, intro, created,
                    codes = OtpExtractor.extract(subject, intro)
                )
            )
        }
        return out
    }

    private fun getMailTm(token: String, id: String, base: String): TempMessage {
        val json = httpJson("GET", "$base/messages/$id", null, token)
        val o = JSONObject(json)
        val from = o.optJSONObject("from")?.optString("address") ?: ""
        val subject = o.optString("subject", "")
        val text = o.optString("text", "")
        val html = when (val h = o.opt("html")) {
            is JSONArray -> (0 until h.length()).joinToString(" ") { h.optString(it) }
            is String -> h
            else -> ""
        }
        val body = text.ifBlank { htmlToTextKeepLinks(html) }
        return TempMessage(
            id, from, subject, o.optString("intro", body.take(120)),
            o.optString("createdAt", ""), body,
            OtpExtractor.extract(subject, body)
        )
    }

    private fun createGuerrilla(user: String?): TempAccount {
        val res = httpJson(
            "GET",
            "https://api.guerrillamail.com/ajax.php?f=get_email_address&lang=en",
            null
        )
        val o = JSONObject(res)
        var sid = o.optString("sid_token")
        var address = o.optString("email_addr")
        if (sid.isBlank()) throw IllegalStateException("Guerrilla بدون sid")
        if (!user.isNullOrBlank()) {
            val res2 = httpJson(
                "GET",
                "https://api.guerrillamail.com/ajax.php?f=set_email_user&email_user=${enc(user)}&sid_token=${enc(sid)}&lang=en",
                null
            )
            val o2 = JSONObject(res2)
            address = o2.optString("email_addr").ifBlank { address }
            sid = o2.optString("sid_token").ifBlank { sid }
        }
        if (address.isBlank()) throw IllegalStateException("آدرس Guerrilla خالی")
        return TempAccount(address = address, token = sid, provider = "guerrilla", apiBase = "https://api.guerrillamail.com")
    }

    private fun listGuerrilla(sid: String): List<TempMessage> {
        val res = httpJson(
            "GET",
            "https://api.guerrillamail.com/ajax.php?f=check_email&sid_token=${enc(sid)}&seq=0",
            null
        )
        val o = JSONObject(res)
        // keep session alive — update sid if returned
        val arr = o.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<TempMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val id = m.optString("mail_id").ifBlank { m.optInt("mail_id", i).toString() }
            // skip welcome placeholder if needed
            val from = m.optString("mail_from")
            val subject = m.optString("mail_subject", "(بدون موضوع)")
            val intro = m.optString("mail_excerpt", "")
            val date = m.optString("mail_date", "")
            out.add(
                TempMessage(
                    id, from, subject, intro, date, intro,
                    OtpExtractor.extract(subject, intro)
                )
            )
        }
        return out
    }

    private fun getGuerrilla(sid: String, id: String): TempMessage {
        val res = httpJson(
            "GET",
            "https://api.guerrillamail.com/ajax.php?f=fetch_email&sid_token=${enc(sid)}&email_id=${enc(id)}",
            null
        )
        val m = JSONObject(res)
        val from = m.optString("mail_from")
        val subject = m.optString("mail_subject", "")
        val body = m.optString("mail_body").ifBlank { m.optString("mail_excerpt") }
        val date = m.optString("mail_date", "")
        return TempMessage(
            id, from, subject, body.take(120), date, body,
            OtpExtractor.extract(subject, body)
        )
    }

    private fun members(json: String): JSONArray {
        val trim = json.trim()
        if (trim.isEmpty()) return JSONArray()
        if (trim.startsWith("[")) return JSONArray(trim)
        val obj = JSONObject(trim)
        return obj.optJSONArray("hydra:member")
            ?: obj.optJSONArray("member")
            ?: obj.optJSONArray("items")
            ?: obj.optJSONArray("list")
            ?: JSONArray()
    }

    private fun htmlToTextKeepLinks(html: String): String {
        if (html.isBlank()) return ""
        var s = html
        s = Regex("(?is)<a[^>]+href\\s*=\\s*[\"'](https?://[^\"']+)[\"'][^>]*>(.*?)</a>").replace(s) { m ->
            val url = m.groupValues[1]
            val label = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            if (label.isNotBlank()) "$label $url " else "$url "
        }
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpJson(method: String, urlStr: String, body: String?, bearer: String? = null): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 25000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 PersianEmail/1.6")
            if (!bearer.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $bearer")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = try {
            if (code in 200..299) conn.inputStream else conn.errorStream
        } catch (_: Exception) {
            null
        }
        val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
        if (code !in 200..299) {
            Log.e(TAG, "HTTP $code $urlStr $text")
            val fa = when (code) {
                401, 403 -> "نشست منقضی — دوباره بسازید یا تازه کنید"
                404 -> "پیدا نشد"
                422 -> "این نام کاربری گرفته شده یا نامعتبر است"
                429 -> "درخواست زیاد — کمی صبر کنید"
                in 500..599 -> "سرور موقتاً قطع است"
                else -> "خطای شبکه ($code)"
            }
            throw IllegalStateException(fa)
        }
        return text
    }
}
