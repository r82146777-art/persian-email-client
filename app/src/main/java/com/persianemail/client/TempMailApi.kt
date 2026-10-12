package com.persianemail.client

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Disposable email via mail.tm API (supports both legacy hydra and plain JSON array).
 */
object TempMailApi {
    private const val TAG = "TempMailApi"
    private val BASES = listOf(
        "https://api.mail.tm",
        "https://api.mail.gw"
    )

    data class TempAccount(
        val address: String,
        val password: String,
        val token: String = "",
        val apiBase: String = BASES.first()
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

    private fun members(json: String): JSONArray {
        val trim = json.trim()
        if (trim.startsWith("[")) return JSONArray(trim)
        val obj = JSONObject(trim)
        return obj.optJSONArray("hydra:member")
            ?: obj.optJSONArray("member")
            ?: obj.optJSONArray("items")
            ?: JSONArray()
    }

    fun fetchDomains(base: String = BASES.first()): List<String> {
        val json = httpGet("$base/domains?page=1")
        val arr = members(json)
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val item = arr.get(i)
            when (item) {
                is JSONObject -> {
                    val d = item.optString("domain").ifBlank { item.optString("name") }
                    val active = item.optBoolean("isActive", true)
                    if (d.isNotBlank() && active) out.add(d)
                }
                is String -> if (item.isNotBlank()) out.add(item)
            }
        }
        // fallback: sometimes /domains returns without page
        if (out.isEmpty()) {
            val json2 = httpGet("$base/domains")
            val arr2 = members(json2)
            for (i in 0 until arr2.length()) {
                val item = arr2.optJSONObject(i) ?: continue
                val d = item.optString("domain")
                if (d.isNotBlank() && item.optBoolean("isActive", true)) out.add(d)
            }
        }
        return out.distinct()
    }

    fun createRandomAccount(): TempAccount {
        var lastErr: Exception? = null
        for (base in BASES) {
            try {
                val domains = fetchDomains(base)
                if (domains.isEmpty()) {
                    lastErr = IllegalStateException("دامنه‌ای از سرور دریافت نشد")
                    continue
                }
                val domain = domains.random()
                val local = "fa" + UUID.randomUUID().toString().replace("-", "").take(10)
                val address = "$local@$domain"
                val password = "Aa1" + UUID.randomUUID().toString().replace("-", "")
                return createAccount(address, password, base)
            } catch (e: Exception) {
                Log.e(TAG, "create via $base", e)
                lastErr = e
            }
        }
        throw lastErr ?: IllegalStateException("ساخت ایمیل موقت ممکن نشد. اینترنت را چک کنید.")
    }

    fun createAccount(address: String, password: String, base: String = BASES.first()): TempAccount {
        val body = JSONObject()
            .put("address", address.lowercase())
            .put("password", password)
            .toString()
        try {
            httpPost("$base/accounts", body, null)
        } catch (e: Exception) {
            // 422 might mean exists — still try login
            Log.w(TAG, "accounts post: ${e.message}")
            if (e.message?.contains("422") != true && e.message?.contains("exist") != true) {
                // continue to token; if fail will throw
            }
        }
        val token = login(address.lowercase(), password, base)
        return TempAccount(address.lowercase(), password, token, base)
    }

    fun login(address: String, password: String, base: String = BASES.first()): String {
        val body = JSONObject()
            .put("address", address.lowercase())
            .put("password", password)
            .toString()
        val res = httpPost("$base/token", body, null)
        val token = JSONObject(res).optString("token")
        if (token.isBlank()) throw IllegalStateException("توکن دریافت نشد")
        return token
    }

    fun listMessages(token: String, base: String = BASES.first()): List<TempMessage> {
        val json = httpGet("$base/messages?page=1", token)
        val arr = members(json)
        val out = ArrayList<TempMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fromObj = o.optJSONObject("from")
            val from = fromObj?.optString("address")
                ?: fromObj?.optString("name")
                ?: o.optString("from", "")
            val subject = o.optString("subject", "(بدون موضوع)")
            val intro = o.optString("intro", "")
            val id = o.optString("id").ifBlank { o.optString("@id").substringAfterLast("/") }
            val created = o.optString("createdAt", "")
            val codes = OtpExtractor.extract(subject, intro)
            if (id.isNotBlank()) {
                out.add(TempMessage(id, from, subject, intro, created, codes = codes))
            }
        }
        return out
    }

    fun getMessage(token: String, id: String, base: String = BASES.first()): TempMessage {
        val json = httpGet("$base/messages/$id", token)
        val o = JSONObject(json)
        val from = o.optJSONObject("from")?.optString("address") ?: ""
        val subject = o.optString("subject", "")
        val text = o.optString("text", "")
        val html = when (val h = o.opt("html")) {
            is JSONArray -> (0 until h.length()).joinToString(" ") { h.optString(it) }
            is String -> h
            else -> ""
        }
        val body = text.ifBlank {
            html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        }
        val intro = o.optString("intro", body.take(120))
        val codes = OtpExtractor.extract(subject, body)
        return TempMessage(
            id = id,
            from = from,
            subject = subject,
            intro = intro,
            createdAt = o.optString("createdAt", ""),
            body = body,
            codes = codes
        )
    }

    private fun httpGet(urlStr: String, token: String? = null): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 25000
            readTimeout = 30000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PersianEmail/1.1")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        return readResponse(conn)
    }

    private fun httpPost(urlStr: String, body: String, token: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PersianEmail/1.1")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        return readResponse(conn)
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

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = try {
            if (code in 200..299) conn.inputStream else conn.errorStream
        } catch (_: Exception) { null }
        val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
        if (code !in 200..299) {
            Log.e(TAG, "HTTP $code $text")
            val fa = when (code) {
                401, 403 -> "دسترسی رد شد. دوباره ایمیل بسازید."
                404 -> "منبع پیدا نشد."
                422 -> "این آدرس قابل استفاده نیست. دوباره تلاش کنید."
                429 -> "درخواست زیاد است. کمی بعد دوباره بزنید."
                in 500..599 -> "سرور ایمیل موقت قطع است. بعداً تلاش کنید."
                else -> "خطای شبکه ($code)"
            }
            throw IllegalStateException(fa)
        }
        return text
    }
}
