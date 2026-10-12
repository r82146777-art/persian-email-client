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
        val smtpPort: Int = 465,
        /** Official signup page — real providers only allow creation on their site */
        val signupUrl: String = "",
        val signupHint: String = ""
    )

    val list = listOf(
        Preset(
            name = "Gmail",
            imap = "imap.gmail.com",
            smtp = "smtp.gmail.com",
            signupUrl = "https://accounts.google.com/Signup",
            signupHint = "بعد از ساخت حساب، برای ورود به برنامه از «رمز برنامه» (App Password) استفاده کنید."
        ),
        Preset(
            name = "Outlook / Hotmail",
            imap = "outlook.office365.com",
            smtp = "smtp.office365.com",
            imapPort = 993,
            smtpPort = 587,
            signupUrl = "https://signup.live.com/signup",
            signupHint = "حساب Outlook/Hotmail را بسازید؛ سپس با همان ایمیل و رمز وارد شوید."
        ),
        Preset(
            name = "Yahoo",
            imap = "imap.mail.yahoo.com",
            smtp = "smtp.mail.yahoo.com",
            signupUrl = "https://login.yahoo.com/account/create",
            signupHint = "پس از ساخت، در تنظیمات یاهو App Password بسازید و اینجا وارد کنید."
        ),
        Preset(
            name = "Zoho",
            imap = "imap.zoho.com",
            smtp = "smtp.zoho.com",
            signupUrl = "https://www.zoho.com/mail/zohomail-signup.html",
            signupHint = "ثبت‌نام Zoho Mail را کامل کنید و با همان مشخصات وارد شوید."
        ),
        Preset(
            name = "Proton Mail",
            imap = "127.0.0.1",
            smtp = "127.0.0.1",
            signupUrl = "https://account.proton.me/signup",
            signupHint = "پروتون معمولاً با Bridge کار می‌کند؛ برای دریافت ساده از ایمیل موقت استفاده کنید یا Bridge را تنظیم کنید."
        ),
        Preset(
            name = "سفارشی (دستی)",
            imap = "",
            smtp = "",
            imapPort = 993,
            smtpPort = 465,
            signupUrl = "",
            signupHint = "سرور IMAP/SMTP را خودتان وارد کنید."
        )
    )
}
