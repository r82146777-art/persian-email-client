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
 * Multi-provider disposable email (Atomic / Temp style).
 * Tries several free APIs so if one is blocked, another works.
 */
object TempMailApi {
    private const val TAG = "TempMailApi"

    data class TempAccount(
        val address: String,
        val password: String = "",
        val token: String = "",
        val apiBase: String = "",
        /** mailtm | tempmaillol | guerrilla | tempmailio */
        val provider: String = "mailtm"
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


    /** Create with chosen username (like Atomic: type name → create). */
    fun createWithUsername(usernameRaw: String, domainPreferred: String? = null): TempAccount {
        val user = usernameRaw.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]"), "")
            .trim('.', '_')
        if (user.length < 3) throw IllegalStateException("نام کاربری حداقل ۳ حرف باشد")
        if (user.length > 30) throw IllegalStateException("نام کاربری حداکثر ۳۰ حرف")

        val errors = ArrayList<String>()
        // 1) mail.tm custom address
        try {
            return createMailTmWithUser(user, domainPreferred)
        } catch (e: Exception) {
            errors.add("Mail.tm: ${e.message}")
        }
        // 2) guerrilla custom
        try {
            return createGuerrillaWithUser(user)
        } catch (e: Exception) {
            errors.add("Guerrilla: ${e.message}")
        }
        // 3) fallback random if custom fails
        try {
            return createRandomAccount()
        } catch (e: Exception) {
            errors.add(e.message ?: "")
        }
        throw IllegalStateException("نتوانست با این نام کاربری بسازد:\n" + errors.joinToString("\n"))
    }

    fun listAvailableDomains(): List<String> {
        val out = LinkedHashSet<String>()
        for (base in listOf("https://api.mail.tm", "https://api.mail.gw")) {
            try {
                out.addAll(fetchMailTmDomains(base))
            } catch (_: Exception) {}
        }
        // common guerrilla domains
        out.add("guerrillamailblock.com")
        out.add("guerrillamail.com")
        out.add("sharklasers.com")
        out.add("grr.la")
        if (out.isEmpty()) out.add("maxxspace.com")
        return out.toList()
    }

    private fun createMailTmWithUser(user: String, domainPreferred: String?): TempAccount {
        var last: Exception? = null
        for (base in listOf("https://api.mail.tm", "https://api.mail.gw")) {
            try {
                val domains = fetchMailTmDomains(base)
                if (domains.isEmpty()) continue
                val domain = when {
                    !domainPreferred.isNullOrBlank() && domains.any { it.equals(domainPreferred, true) } -> domainPreferred
                    !domainPreferred.isNullOrBlank() && domainPreferred.contains("guerrilla", true) -> domains.random()
                    else -> domains.random()
                }
                val address = "$user@$domain"
                val password = "Aa1" + UUID.randomUUID().toString().replace("-", "")
                try {
                    httpJson("POST", "$base/accounts", JSONObject().put("address", address).put("password", password).toString())
                } catch (e: Exception) {
                    // if taken, try suffix
                    if ((e.message ?: "").contains("422") || (e.message ?: "").contains("نامعتبر")) {
                        val address2 = user + UUID.randomUUID().toString().replace("-", "").take(4) + "@" + domain
                        httpJson("POST", "$base/accounts", JSONObject().put("address", address2).put("password", password).toString())
                        val tokenRes = httpJson("POST", "$base/token", JSONObject().put("address", address2).put("password", password).toString())
                        val token = JSONObject(tokenRes).optString("token")
                        if (token.isBlank()) throw IllegalStateException("توکن خالی")
                        return TempAccount(address2, password, token, base, "mailtm")
                    }
                    throw e
                }
                val tokenRes = httpJson("POST", "$base/token", JSONObject().put("address", address).put("password", password).toString())
                val token = JSONObject(tokenRes).optString("token")
                if (token.isBlank()) throw IllegalStateException("توکن خالی")
                return TempAccount(address, password, token, base, "mailtm")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("mail.tm در دسترس نیست")
    }

    private fun createGuerrillaWithUser(user: String): TempAccount {
        val res = httpJson("GET", "https://api.guerrillamail.com/ajax.php?f=get_email_address&lang=en", null)
        val o = JSONObject(res)
        var sid = o.optString("sid_token")
        if (sid.isBlank()) throw IllegalStateException("Guerrilla بدون sid")
        val res2 = httpJson(
            "GET",
            "https://api.guerrillamail.com/ajax.php?f=set_email_user&email_user=${enc(user)}&sid_token=${enc(sid)}&lang=en",
            null
        )
        val o2 = JSONObject(res2)
        val address = o2.optString("email_addr")
        sid = o2.optString("sid_token").ifBlank { sid }
        if (address.isBlank()) throw IllegalStateException("Guerrilla نتوانست نام کاربری را ست کند")
        return TempAccount(address = address, token = sid, provider = "guerrilla", apiBase = "https://api.guerrillamail.com")
    }

    fun createRandomAccount(): TempAccount {
        val errors = ArrayList<String>()
        // Order: often most reachable first
        val creators = listOf(
            ::createTempMailLol,
            ::createMailTm,
            ::createGuerrilla,
            ::createTempMailIo
        )
        for (creator in creators) {
            try {
                return creator()
            } catch (e: Exception) {
                Log.e(TAG, "provider fail", e)
                errors.add(e.message ?: e.javaClass.simpleName)
            }
        }
        throw IllegalStateException(
            "ساخت ایمیل موقت ممکن نشد.\n" + errors.take(3).joinToString("\n")
        )
    }

    fun listMessages(account: TempAccount): List<TempMessage> {
        return when (account.provider) {
            "tempmaillol" -> listTempMailLol(account.token)
            "guerrilla" -> listGuerrilla(account.token)
            "tempmailio" -> listTempMailIo(account.address, account.token)
            else -> listMailTm(account.token, account.apiBase.ifBlank { "https://api.mail.tm" })
        }
    }

    fun getMessage(account: TempAccount, id: String): TempMessage {
        return when (account.provider) {
            "tempmaillol" -> getTempMailLol(account.token, id)
            "guerrilla" -> getGuerrilla(account.token, id)
            "tempmailio" -> getTempMailIo(account.address, account.token, id)
            else -> getMailTm(account.token, id, account.apiBase.ifBlank { "https://api.mail.tm" })
        }
    }

    // ---------- tempmail.lol ----------
    private fun createTempMailLol(): TempAccount {
        val res = httpJson("POST", "https://api.tempmail.lol/v2/inbox/create", "{}")
        val o = JSONObject(res)
        val address = o.optString("address").ifBlank { o.optString("email") }
        val token = o.optString("token")
        if (address.isBlank() || token.isBlank()) throw IllegalStateException("tempmail.lol پاسخ ناقص")
        return TempAccount(address = address, token = token, provider = "tempmaillol", apiBase = "https://api.tempmail.lol")
    }

    private fun listTempMailLol(token: String): List<TempMessage> {
        val res = httpJson("GET", "https://api.tempmail.lol/v2/inbox?token=" + enc(token), null)
        val o = JSONObject(res)
        if (o.optBoolean("expired", false)) throw IllegalStateException("این آدرس منقضی شد — ایمیل جدید بسازید")
        val arr = o.optJSONArray("emails") ?: JSONArray()
        val out = ArrayList<TempMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val id = m.optString("id").ifBlank { m.optString("_id").ifBlank { i.toString() } }
            val from = m.optString("from").ifBlank { m.optString("sender") }
            val subject = m.optString("subject", "(بدون موضوع)")
            val body = m.optString("body").ifBlank { m.optString("html").ifBlank { m.optString("text") } }
            val intro = m.optString("body_text").ifBlank { body.take(120) }
            val date = m.optString("date").ifBlank { m.optString("createdAt") }
            val codes = OtpExtractor.extract(subject, body.ifBlank { intro })
            out.add(TempMessage(id, from, subject, intro, date, body, codes))
        }
        return out
    }

    private fun getTempMailLol(token: String, id: String): TempMessage {
        val list = listTempMailLol(token)
        return list.firstOrNull { it.id == id }
            ?: list.firstOrNull()
            ?: TempMessage(id, "", "", "", "", "", emptyList())
    }

    // ---------- mail.tm / mail.gw ----------
    private fun createMailTm(): TempAccount {
        var last: Exception? = null
        for (base in listOf("https://api.mail.tm", "https://api.mail.gw")) {
            try {
                val domains = fetchMailTmDomains(base)
                if (domains.isEmpty()) throw IllegalStateException("بدون دامنه ($base)")
                val domain = domains.random()
                val address = "fa" + UUID.randomUUID().toString().replace("-", "").take(10) + "@" + domain
                val password = "Aa1" + UUID.randomUUID().toString().replace("-", "")
                try {
                    httpJson("POST", "$base/accounts", JSONObject().put("address", address).put("password", password).toString())
                } catch (_: Exception) { /* may already exist */ }
                val tokenRes = httpJson("POST", "$base/token", JSONObject().put("address", address).put("password", password).toString())
                val token = JSONObject(tokenRes).optString("token")
                if (token.isBlank()) throw IllegalStateException("توکن mail.tm خالی")
                return TempAccount(address, password, token, base, "mailtm")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("mail.tm در دسترس نیست")
    }

    private fun fetchMailTmDomains(base: String): List<String> {
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
            val from = o.optJSONObject("from")?.optString("address") ?: o.optString("from")
            val subject = o.optString("subject", "(بدون موضوع)")
            val intro = o.optString("intro", "")
            val id = o.optString("id").ifBlank { o.optString("@id").substringAfterLast("/") }
            val created = o.optString("createdAt", "")
            val codes = OtpExtractor.extract(subject, intro)
            if (id.isNotBlank()) out.add(TempMessage(id, from, subject, intro, created, codes = codes))
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
        return TempMessage(id, from, subject, o.optString("intro", body.take(120)), o.optString("createdAt", ""), body, OtpExtractor.extract(subject, body))
    }

    // ---------- Guerrilla Mail ----------
    private fun createGuerrilla(): TempAccount {
        val res = httpJson("GET", "https://api.guerrillamail.com/ajax.php?f=get_email_address&lang=en", null)
        val o = JSONObject(res)
        val address = o.optString("email_addr")
        val sid = o.optString("sid_token")
        if (address.isBlank() || sid.isBlank()) throw IllegalStateException("Guerrilla پاسخ ناقص")
        return TempAccount(address = address, token = sid, provider = "guerrilla", apiBase = "https://api.guerrillamail.com")
    }

    private fun listGuerrilla(sid: String): List<TempMessage> {
        val res = httpJson("GET", "https://api.guerrillamail.com/ajax.php?f=check_email&sid_token=${enc(sid)}&seq=0", null)
        val o = JSONObject(res)
        val arr = o.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<TempMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val id = m.optString("mail_id").ifBlank { m.optInt("mail_id", i).toString() }
            val from = m.optString("mail_from")
            val subject = m.optString("mail_subject", "(بدون موضوع)")
            val intro = m.optString("mail_excerpt", "")
            val date = m.optString("mail_date", "")
            val codes = OtpExtractor.extract(subject, intro)
            out.add(TempMessage(id, from, subject, intro, date, intro, codes))
        }
        return out
    }

    private fun getGuerrilla(sid: String, id: String): TempMessage {
        val res = httpJson("GET", "https://api.guerrillamail.com/ajax.php?f=fetch_email&sid_token=${enc(sid)}&email_id=${enc(id)}", null)
        val m = JSONObject(res)
        val from = m.optString("mail_from")
        val subject = m.optString("mail_subject", "")
        val body = m.optString("mail_body").ifBlank { m.optString("mail_excerpt") }
        val date = m.optString("mail_date", "")
        return TempMessage(id, from, subject, body.take(120), date, body, OtpExtractor.extract(subject, body))
    }

    // ---------- temp-mail.io ----------
    private fun createTempMailIo(): TempAccount {
        val res = httpJson("POST", "https://api.internal.temp-mail.io/api/v3/email/new", "{}")
        val o = JSONObject(res)
        val email = o.optString("email")
        val token = o.optString("token")
        if (email.isBlank()) throw IllegalStateException("temp-mail.io پاسخ ناقص")
        return TempAccount(address = email, token = token, provider = "tempmailio", apiBase = "https://api.internal.temp-mail.io")
    }

    private fun listTempMailIo(email: String, token: String): List<TempMessage> {
        val url = "https://api.internal.temp-mail.io/api/v3/email/${enc(email)}/messages"
        val res = try {
            httpJson("GET", url, null, token)
        } catch (_: Exception) {
            httpJson("GET", url, null, null)
        }
        val arr = members(res)
        val out = ArrayList<TempMessage>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id").ifBlank { m.optString("_id").ifBlank { i.toString() } }
            val from = m.optString("from").ifBlank {
                m.optJSONObject("from")?.optString("address") ?: ""
            }
            val subject = m.optString("subject", "(بدون موضوع)")
            val body = m.optString("body_text").ifBlank { m.optString("body").ifBlank { m.optString("text") } }
            val intro = m.optString("body_preview").ifBlank { body.take(120) }
            val date = m.optString("created_at").ifBlank { m.optString("date") }
            out.add(TempMessage(id, from, subject, intro, date, body, OtpExtractor.extract(subject, body.ifBlank { intro })))
        }
        return out
    }

    private fun getTempMailIo(email: String, token: String, id: String): TempMessage {
        val list = listTempMailIo(email, token)
        val found = list.firstOrNull { it.id == id }
        if (found != null && found.body.isNotBlank()) return found
        val url = "https://api.internal.temp-mail.io/api/v3/email/${enc(email)}/messages/${enc(id)}"
        return try {
            val res = httpJson("GET", url, null, token)
            val m = JSONObject(res)
            val subject = m.optString("subject", "")
            val body = m.optString("body_text").ifBlank { m.optString("body") }
            TempMessage(id, m.optString("from"), subject, body.take(120), m.optString("created_at"), body, OtpExtractor.extract(subject, body))
        } catch (_: Exception) {
            found ?: TempMessage(id, "", "", "", "", "", emptyList())
        }
    }

    // ---------- shared HTTP ----------
    private fun members(json: String): JSONArray {
        val trim = json.trim()
        if (trim.isEmpty()) return JSONArray()
        if (trim.startsWith("[")) return JSONArray(trim)
        val obj = JSONObject(trim)
        return obj.optJSONArray("hydra:member")
            ?: obj.optJSONArray("member")
            ?: obj.optJSONArray("items")
            ?: obj.optJSONArray("list")
            ?: obj.optJSONArray("emails")
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
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Mozilla/5.0 PersianEmail/1.4")
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
        } catch (_: Exception) { null }
        val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
        if (code !in 200..299) {
            Log.e(TAG, "HTTP $code $urlStr $text")
            val fa = when (code) {
                401, 403 -> "دسترسی رد شد"
                404 -> "پیدا نشد"
                422 -> "درخواست نامعتبر"
                429 -> "درخواست زیاد — کمی صبر کنید"
                in 500..599 -> "سرور موقتاً قطع است"
                else -> "خطای شبکه ($code)"
            }
            throw IllegalStateException(fa)
        }
        return text
    }
}
