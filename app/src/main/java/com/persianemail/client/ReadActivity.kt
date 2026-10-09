package com.persianemail.client

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        binding.body.text = msg.body.ifBlank { "(بدون متن)" }

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
    }
}
