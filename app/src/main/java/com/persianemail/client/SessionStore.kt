package com.persianemail.client

import android.content.Context

object SessionStore {
    private const val PREFS = "persian_email"
    private const val K_EMAIL = "email"
    private const val K_PASS = "pass"
    private const val K_IMAP = "imap"
    private const val K_SMTP = "smtp"
    private const val K_IMAP_PORT = "imap_port"
    private const val K_SMTP_PORT = "smtp_port"
    private const val K_PROVIDER = "provider"

    fun save(context: Context, account: MailAccount) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_EMAIL, account.email)
            .putString(K_PASS, account.password)
            .putString(K_IMAP, account.imapHost)
            .putString(K_SMTP, account.smtpHost)
            .putInt(K_IMAP_PORT, account.imapPort)
            .putInt(K_SMTP_PORT, account.smtpPort)
            .putString(K_PROVIDER, account.providerName)
            .apply()
    }

    fun load(context: Context): MailAccount? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val email = p.getString(K_EMAIL, null) ?: return null
        val pass = p.getString(K_PASS, null) ?: return null
        val imap = p.getString(K_IMAP, null) ?: return null
        val smtp = p.getString(K_SMTP, null) ?: return null
        return MailAccount(
            email = email,
            password = pass,
            imapHost = imap,
            imapPort = p.getInt(K_IMAP_PORT, 993),
            smtpHost = smtp,
            smtpPort = p.getInt(K_SMTP_PORT, 465),
            providerName = p.getString(K_PROVIDER, "") ?: ""
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
