package com.persianemail.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.persianemail.client.databinding.ActivityReadBinding

class ReadActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReadBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        @Suppress("DEPRECATION")
        val msg = intent.getSerializableExtra("message") as? MailMessage
        if (msg == null) {
            finish()
            return
        }

        supportActionBar?.title = msg.subject.take(40)
        binding.subject.text = msg.subject
        binding.from.text = "از: ${msg.from}"
        binding.date.text = msg.date

        val rawBody = msg.body.ifBlank { "(بدون متن)" }
        val pair = LinkExtractor.linkifyBody(rawBody)
        val displayBody = pair.first
        val allLinks = LinkExtractor.extract(msg.subject, rawBody).distinctBy { it.url }

        binding.body.text = buildClickableBody(displayBody)
        binding.body.movementMethod = LinkMovementMethod.getInstance()
        binding.body.highlightColor = Color.TRANSPARENT
        binding.body.contentDescription =
            if (allLinks.isNotEmpty()) "متن نامه با ${allLinks.size} لینک قابل کلیک"
            else "متن نامه"

        if (msg.codes.isNotEmpty()) {
            binding.otpBox.visibility = View.VISIBLE
            binding.otpCodes.text = msg.codes.joinToString("   ")
            binding.otpBox.announceForAccessibility("کدهای تأیید: ${msg.codes.joinToString()}")
            binding.copyCodeBtn.setOnClickListener {
                val code = msg.codes.first()
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("otp", code))
                Toast.makeText(this, "کد $code کپی شد", Toast.LENGTH_SHORT).show()
                binding.copyCodeBtn.announceForAccessibility("کد کپی شد")
            }
        } else {
            binding.otpBox.visibility = View.GONE
        }

        if (allLinks.isNotEmpty()) {
            binding.linksBox.visibility = View.VISIBLE
            binding.linksContainer.removeAllViews()
            binding.linksBox.announceForAccessibility("${allLinks.size} لینک تأیید پیدا شد")
            for ((index, link) in allLinks.withIndex()) {
                val btn = MaterialButton(
                    this,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                )
                btn.text = if (link.label.isNotBlank() && link.label != link.url) {
                    link.label + "\n" + link.url
                } else {
                    link.url
                }
                btn.isAllCaps = false
                btn.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                btn.contentDescription =
                    "لینک ${index + 1}: ${link.label}. برای باز کردن دوبار ضربه بزنید"
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = 8
                btn.layoutParams = lp
                btn.setOnClickListener { openUrl(link.url) }
                btn.setOnLongClickListener {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("link", link.url))
                    Toast.makeText(this, "لینک کپی شد", Toast.LENGTH_SHORT).show()
                    btn.announceForAccessibility("لینک کپی شد")
                    true
                }
                binding.linksContainer.addView(btn)
            }
        } else {
            binding.linksBox.visibility = View.GONE
        }
    }

    private fun buildClickableBody(text: String): SpannableString {
        val span = SpannableString(text)
        val urlRegex = Regex("""https?://[^\s<>]+""", RegexOption.IGNORE_CASE)
        for (m in urlRegex.findAll(text)) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start < 0 || end > span.length) continue
            val url = m.value.trimEnd('.', ',', ')', ']', '}', '"', '\'')
            val click = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openUrl(url)
                }
            }
            span.setSpan(click, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(
                ForegroundColorSpan(0xFF1565C0.toInt()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return span
    }

    private fun openUrl(url: String) {
        try {
            val u = if (url.startsWith("http")) url else "https://$url"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        } catch (e: Exception) {
            Toast.makeText(this, "نتوانست لینک را باز کند", Toast.LENGTH_SHORT).show()
        }
    }
}
