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
 * Disposable email via mail.tm public API.
 * Address + password stored locally so inbox survives app restart.
 */
object TempMailApi {
    private const val TAG = "TempMailApi"
    private const val BASE = "https://api.mail.tm"

    data class TempAccount(
        val address: String,
        val password: String,
        val token: String = ""
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

    fun fetchDomains(): List<String> {
        val json = httpGet("$BASE/domains?page=1")
        val arr = JSONObject(json).optJSONArray("hydra:member") ?: JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            if (d.optBoolean("isActive", true)) out.add(d.getString("domain"))
        }
        return out
    }

    /** Create a new random temp mailbox (persisted by caller). */
    fun createRandomAccount(): TempAccount {
        val domains = fetchDomains()
        if (domains.isEmpty()) throw IllegalStateException("دامنه موقتی در دسترس نیست")
        val domain = domains.random()
        val local = "fa" + UUID.randomUUID().toString().replace("-", "").take(10)
        val address = "$local@$domain"
        val password = UUID.randomUUID().toString().replace("-", "") + "Aa1"
        return createAccount(address, password)
    }

    fun createAccount(address: String, password: String): TempAccount {
        val body = JSONObject()
            .put("address", address)
            .put("password", password)
            .toString()
        httpPost("$BASE/accounts", body, null)
        val token = login(address, password)
        return TempAccount(address, password, token)
    }

    fun login(address: String, password: String): String {
        val body = JSONObject()
            .put("address", address)
            .put("password", password)
            .toString()
        val res = httpPost("$BASE/token", body, null)
        return JSONObject(res).getString("token")
    }

    fun listMessages(token: String): List<TempMessage> {
        val json = httpGet("$BASE/messages?page=1", token)
        val arr = JSONObject(json).optJSONArray("hydra:member") ?: JSONArray()
        val out = ArrayList<TempMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val from = o.optJSONObject("from")?.optString("address")
                ?: o.optString("from", "")
            val subject = o.optString("subject", "(بدون موضوع)")
            val intro = o.optString("intro", "")
            val id = o.getString("id")
            val created = o.optString("createdAt", "")
            val codes = OtpExtractor.extract(subject, intro)
            out.add(TempMessage(id, from, subject, intro, created, codes = codes))
        }
        return out
    }

    fun getMessage(token: String, id: String): TempMessage {
        val json = httpGet("$BASE/messages/$id", token)
        val o = JSONObject(json)
        val from = o.optJSONObject("from")?.optString("address") ?: ""
        val subject = o.optString("subject", "")
        val text = o.optString("text", "")
        val html = o.optString("html", "")
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
            connectTimeout = 20000
            readTimeout = 25000
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        return readResponse(conn)
    }

    private fun httpPost(urlStr: String, body: String, token: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 25000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
        return readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
        if (code !in 200..299) {
            Log.e(TAG, "HTTP $code $text")
            throw IllegalStateException("خطای سرور ($code): ${text.take(120)}")
        }
        return text
    }
}
