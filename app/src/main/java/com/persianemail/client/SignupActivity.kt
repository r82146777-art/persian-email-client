package com.persianemail.client

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.persianemail.client.databinding.ActivitySignupBinding

class SignupActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignupBinding
    private var signupUrl: String = ""
    private var providerName: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener {
            if (binding.webView.visibility == View.VISIBLE && binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                finish()
            }
        }

        providerName = intent.getStringExtra("provider_name") ?: "سرویس"
        signupUrl = intent.getStringExtra("signup_url") ?: ""
        val hint = intent.getStringExtra("signup_hint") ?: ""

        binding.providerTitle.text = "ایجاد حساب $providerName"
        binding.hint.text = hint
        binding.toolbar.title = "ایجاد $providerName"

        if (signupUrl.isBlank()) {
            Toast.makeText(this, "برای این گزینه ثبت‌نام آنلاین تعریف نشده", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.btnOpenSignup.setOnClickListener { openWeb() }
        binding.btnDoneLogin.setOnClickListener { finish() }

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true
        binding.webView.settings.userAgentString =
            binding.webView.settings.userAgentString + " PersianEmailApp"
        binding.webView.webChromeClient = WebChromeClient()
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
        }
    }

    private fun openWeb() {
        val name = binding.fullName.text?.toString()?.trim().orEmpty()
        val user = binding.username.text?.toString()?.trim().orEmpty()
        val pass = binding.password.text?.toString()?.trim().orEmpty()
        // Keep form values in status for user convenience (clipboard-friendly)
        if (user.isNotEmpty() || name.isNotEmpty()) {
            val summary = buildString {
                if (name.isNotEmpty()) append("نام: $name\n")
                if (user.isNotEmpty()) append("کاربری: $user\n")
                if (pass.isNotEmpty()) append("رمز پیشنهادی ذخیره در حافظه کوتاه — در فرم رسمی وارد کنید")
            }
            Toast.makeText(this, "صفحه رسمی باز شد. مشخصات را آنجا وارد کنید.", Toast.LENGTH_LONG).show()
        }
        binding.formScroll.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.loadUrl(signupUrl)
        binding.webView.announceForAccessibility("صفحه ثبت‌نام $providerName باز شد")
    }

    override fun onBackPressed() {
        if (binding.webView.visibility == View.VISIBLE && binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else if (binding.webView.visibility == View.VISIBLE) {
            binding.webView.visibility = View.GONE
            binding.formScroll.visibility = View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }
}
