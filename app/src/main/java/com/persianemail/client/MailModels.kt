package com.persianemail.client

import java.io.Serializable

data class MailAccount(
    val email: String,
    val password: String,
    val imapHost: String,
    val imapPort: Int = 993,
    val smtpHost: String,
    val smtpPort: Int = 465,
    val providerName: String = "custom"
) : Serializable

data class MailMessage(
    val uid: Long,
    val from: String,
    val subject: String,
    val date: String,
    val preview: String,
    val body: String,
    val codes: List<String>,
    val unread: Boolean
) : Serializable

object Providers {
    data class Preset(
        val name: String,
        val imap: String,
        val smtp: String,
        val imapPort: Int = 993,
        val smtpPort: Int = 465
    )

    val list = listOf(
        Preset("Gmail", "imap.gmail.com", "smtp.gmail.com"),
        Preset("Outlook / Hotmail", "outlook.office365.com", "smtp.office365.com", 993, 587),
        Preset("Yahoo", "imap.mail.yahoo.com", "smtp.mail.yahoo.com"),
        Preset("Zoho", "imap.zoho.com", "smtp.zoho.com"),
        Preset("سفارشی (دستی)", "", "", 993, 465)
    )
}
