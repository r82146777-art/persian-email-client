package com.persianemail.client

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Store
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeBodyPart

object MailService {
    private const val TAG = "MailService"

    @Throws(Exception::class)
    fun testLogin(account: MailAccount) {
        val store = openStore(account)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            inbox.close(false)
        } finally {
            try { store.close() } catch (_: Exception) {}
        }
    }

    @Throws(Exception::class)
    fun fetchInbox(account: MailAccount, limit: Int = 40): List<MailMessage> {
        val store = openStore(account)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val total = inbox.messageCount
            if (total == 0) return emptyList()
            val start = (total - limit + 1).coerceAtLeast(1)
            val msgs = inbox.getMessages(start, total)
            // newest first
            val result = ArrayList<MailMessage>(msgs.size)
            for (i in msgs.indices.reversed()) {
                val m = msgs[i]
                result.add(toMailMessage(m, i + start.toLong()))
            }
            inbox.close(false)
            return result
        } finally {
            try { store.close() } catch (_: Exception) {}
        }
    }

    @Throws(Exception::class)
    fun fetchMessage(account: MailAccount, messageNumber: Int): MailMessage {
        val store = openStore(account)
        try {
            val inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_ONLY)
            val m = inbox.getMessage(messageNumber)
            val mail = toMailMessage(m, messageNumber.toLong())
            inbox.close(false)
            return mail
        } finally {
            try { store.close() } catch (_: Exception) {}
        }
    }

    @Throws(Exception::class)
    fun send(account: MailAccount, to: String, subject: String, body: String) {
        val props = Properties()
        props["mail.smtp.host"] = account.smtpHost
        props["mail.smtp.port"] = account.smtpPort.toString()
        props["mail.smtp.auth"] = "true"
        if (account.smtpPort == 465) {
            props["mail.smtp.ssl.enable"] = "true"
            props["mail.smtp.socketFactory.class"] = "javax.net.ssl.SSLSocketFactory"
            props["mail.smtp.socketFactory.port"] = "465"
        } else {
            props["mail.smtp.starttls.enable"] = "true"
        }
        props["mail.smtp.ssl.trust"] = account.smtpHost
        props["mail.smtp.connectiontimeout"] = "20000"
        props["mail.smtp.timeout"] = "20000"

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(account.email, account.password)
        })
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress(account.email))
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false))
        msg.subject = subject
        msg.setText(body, "UTF-8")
        msg.sentDate = Date()
        Transport.send(msg)
    }

    private fun openStore(account: MailAccount): Store {
        val props = Properties()
        props["mail.store.protocol"] = "imaps"
        props["mail.imaps.host"] = account.imapHost
        props["mail.imaps.port"] = account.imapPort.toString()
        props["mail.imaps.ssl.enable"] = "true"
        props["mail.imaps.ssl.trust"] = account.imapHost
        props["mail.imaps.connectiontimeout"] = "25000"
        props["mail.imaps.timeout"] = "25000"
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(account.imapHost, account.imapPort, account.email, account.password)
        return store
    }

    private fun toMailMessage(m: Message, uid: Long): MailMessage {
        val from = try {
            m.from?.joinToString { it.toString() } ?: "نامشخص"
        } catch (_: Exception) { "نامشخص" }
        val subject = try { m.subject ?: "(بدون موضوع)" } catch (_: Exception) { "(بدون موضوع)" }
        val date = try {
            val d = m.receivedDate ?: m.sentDate
            if (d != null) SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(d) else ""
        } catch (_: Exception) { "" }
        val body = extractText(m)
        val preview = body.replace("\n", " ").trim().take(120)
        val codes = OtpExtractor.extract(subject, body)
        val unread = try { !m.isSet(Flags.Flag.SEEN) } catch (_: Exception) { false }
        return MailMessage(uid, from, subject, date, preview, body, codes, unread)
    }

    private fun extractText(m: Message): String {
        return try {
            when (val content = m.content) {
                is String -> content
                is MimeMultipart -> extractMultipart(content)
                else -> content?.toString() ?: ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "body", e)
            try { m.content?.toString() ?: "" } catch (_: Exception) { "" }
        }
    }

    private fun extractMultipart(mp: MimeMultipart): String {
        val sb = StringBuilder()
        for (i in 0 until mp.count) {
            val part = mp.getBodyPart(i)
            val ct = part.contentType?.lowercase() ?: ""
            when {
                part.isMimeType("text/plain") -> sb.append(part.content?.toString() ?: "")
                part.isMimeType("text/html") && sb.isEmpty() -> {
                    val html = part.content?.toString() ?: ""
                    sb.append(html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " "))
                }
                part.content is MimeMultipart -> sb.append(extractMultipart(part.content as MimeMultipart))
                part is MimeBodyPart && part.isMimeType("multipart/*") -> {
                    try {
                        sb.append(extractMultipart(part.content as MimeMultipart))
                    } catch (_: Exception) {}
                }
            }
            if (ct.contains("text/plain")) break
        }
        return sb.toString().trim()
    }

    private fun htmlToTextKeepLinks(html: String): String {
        var s = html
        s = Regex("(?is)<a[^>]+href\\s*=\\s*[\"'](https?://[^\"']+)[\"'][^>]*>(.*?)</a>").replace(s) { m ->
            val url = m.groupValues[1]
            val label = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            if (label.isNotBlank()) "$label $url " else "$url "
        }
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</p>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}
