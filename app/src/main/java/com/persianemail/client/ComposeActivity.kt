package com.persianemail.client

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.persianemail.client.databinding.ActivityComposeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComposeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityComposeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val account = SessionStore.load(this)
        if (account == null) {
            finish()
            return
        }

        binding.sendButton.setOnClickListener {
            val to = binding.toInput.text?.toString()?.trim().orEmpty()
            val subject = binding.subjectInput.text?.toString()?.trim().orEmpty()
            val body = binding.bodyInput.text?.toString()?.trim().orEmpty()
            if (to.isEmpty()) {
                Toast.makeText(this, "گیرنده را وارد کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.sendButton.isEnabled = false
            binding.sendButton.text = "در حال ارسال…"
            lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) {
                    try {
                        MailService.send(account, to, subject, body)
                        null
                    } catch (e: Exception) {
                        e.message
                    }
                }
                binding.sendButton.isEnabled = true
                binding.sendButton.text = getString(R.string.send)
                if (err != null) {
                    Toast.makeText(this@ComposeActivity, "خطا: $err", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ComposeActivity, "ارسال شد", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }
}
