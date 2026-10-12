package com.persianemail.client

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.persianemail.client.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // auto login if session exists
        SessionStore.load(this)?.let {
            startActivity(Intent(this, InboxActivity::class.java))
            finish()
            return
        }

        val names = Providers.list.map { it.name }
        binding.providerSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        binding.providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val custom = position == Providers.list.lastIndex
                binding.imapHostLayout.visibility = if (custom) View.VISIBLE else View.GONE
                binding.smtpHostLayout.visibility = if (custom) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.loginButton.setOnClickListener { doLogin() }
        binding.signupButton.setOnClickListener { openSignup() }
        binding.tempMailButton.setOnClickListener {
            startActivity(Intent(this, TempMailActivity::class.java))
        }
    }

    private fun openSignup() {
        val pos = binding.providerSpinner.selectedItemPosition.coerceIn(0, Providers.list.lastIndex)
        val preset = Providers.list[pos]
        if (preset.signupUrl.isBlank()) {
            Toast.makeText(this, "برای «سفارشی» ثبت‌نام آنلاین نیست. سرویس دیگری انتخاب کنید یا ایمیل موقت بسازید.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, SignupActivity::class.java)
                .putExtra("provider_name", preset.name)
                .putExtra("signup_url", preset.signupUrl)
                .putExtra("signup_hint", preset.signupHint)
        )
    }

    private fun doLogin() {

        val email = binding.emailInput.text?.toString()?.trim().orEmpty()
        val pass = binding.passwordInput.text?.toString()?.trim().orEmpty()
        if (email.isEmpty() || pass.isEmpty()) {
            binding.status.text = "ایمیل و رمز را وارد کنید"
            return
        }
        val pos = binding.providerSpinner.selectedItemPosition.coerceIn(0, Providers.list.lastIndex)
        val preset = Providers.list[pos]
        val imap = if (preset.imap.isNotBlank()) preset.imap
        else binding.imapHost.text?.toString()?.trim().orEmpty()
        val smtp = if (preset.smtp.isNotBlank()) preset.smtp
        else binding.smtpHost.text?.toString()?.trim().orEmpty()
        if (imap.isBlank() || smtp.isBlank()) {
            binding.status.text = "آدرس سرور IMAP و SMTP را وارد کنید"
            return
        }
        val account = MailAccount(
            email = email,
            password = pass,
            imapHost = imap,
            imapPort = preset.imapPort,
            smtpHost = smtp,
            smtpPort = preset.smtpPort,
            providerName = preset.name
        )

        binding.progress.visibility = View.VISIBLE
        binding.loginButton.isEnabled = false
        binding.status.text = "در حال اتصال…"
        binding.status.announceForAccessibility("در حال اتصال به سرور ایمیل")

        lifecycleScope.launch {
            val err = withContext(Dispatchers.IO) {
                try {
                    MailService.testLogin(account)
                    null
                } catch (e: Exception) {
                    e.message ?: "خطای اتصال"
                }
            }
            binding.progress.visibility = View.GONE
            binding.loginButton.isEnabled = true
            if (err != null) {
                binding.status.text = "خطا: $err"
                binding.status.announceForAccessibility("ورود ناموفق")
                Toast.makeText(this@LoginActivity, "ورود ناموفق", Toast.LENGTH_SHORT).show()
            } else {
                SessionStore.save(this@LoginActivity, account)
                binding.status.announceForAccessibility("ورود موفق")
                startActivity(Intent(this@LoginActivity, InboxActivity::class.java))
                finish()
            }
        }
    }
}
