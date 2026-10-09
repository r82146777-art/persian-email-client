package com.persianemail.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.persianemail.client.databinding.ActivityTempMailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TempMailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTempMailBinding
    private lateinit var adapter: EmailAdapter
    private var token: String = ""
    private var address: String = ""
    private var apiBase: String = "https://api.mail.tm"
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTempMailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = EmailAdapter { msg ->
            // open read with temp body fetch
            openTempMessage(msg.uid.toString())
        }
        // We'll map TempMessage -> MailMessage for reuse
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnCopy.setOnClickListener { copyAddress() }
        binding.btnCreate.setOnClickListener { createNew() }
        binding.btnSwitch.setOnClickListener { showManage() }
        binding.swipe.setOnRefreshListener { refreshMessages() }

        ensureAccountThenLoad()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    private fun ensureAccountThenLoad() {
        val existing = TempMailStore.getActive(this)?.let { TempMailStore.getAccount(this, it) }
        if (existing != null) {
            bindAccount(existing)
            refreshMessages()
        } else {
            createNew()
        }
    }

    private fun bindAccount(acc: TempMailApi.TempAccount) {
        address = acc.address
        token = acc.token
        apiBase = acc.apiBase.ifBlank { "https://api.mail.tm" }
        binding.activeAddress.text = address
        binding.activeAddress.contentDescription = "آدرس فعال $address"
    }

    private fun createNew() {
        binding.swipe.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(TempMailApi.createRandomAccount())
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            binding.swipe.isRefreshing = false
            result.onSuccess { acc ->
                TempMailStore.save(this@TempMailActivity, acc)
                bindAccount(acc)
                adapter.submit(emptyList())
                Toast.makeText(this@TempMailActivity, "ایمیل جدید ساخته شد", Toast.LENGTH_SHORT).show()
                binding.activeAddress.announceForAccessibility("ایمیل جدید ${acc.address}")
                refreshMessages()
            }.onFailure { e ->
                val msg = e.message ?: "ساخت ایمیل ناموفق"
                Toast.makeText(this@TempMailActivity, msg, Toast.LENGTH_LONG).show()
                binding.activeAddress.announceForAccessibility(msg)
            }
        }
    }

    private fun copyAddress() {
        if (address.isBlank()) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("temp-email", address))
        Toast.makeText(this, "آدرس کپی شد", Toast.LENGTH_SHORT).show()
    }

    private fun showManage() {
        val accounts = TempMailStore.list(this)
        if (accounts.isEmpty()) {
            Toast.makeText(this, "هنوز ایمیلی ذخیره نشده", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = accounts.map { it.address }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("ایمیل‌های ذخیره‌شده")
            .setItems(labels) { _, which ->
                val acc = accounts[which]
                lifecycleScope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        try {
                            val base = acc.apiBase.ifBlank { "https://api.mail.tm" }
                            val t = if (acc.token.isBlank()) TempMailApi.login(acc.address, acc.password, base) else acc.token
                            acc.copy(token = t)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (updated != null) {
                        TempMailStore.save(this@TempMailActivity, updated)
                        bindAccount(updated)
                        refreshMessages()
                    } else {
                        Toast.makeText(this@TempMailActivity, "ورود به این آدرس ناموفق بود", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("حذف فعال") { _, _ ->
                if (address.isNotBlank()) {
                    TempMailStore.remove(this, address)
                    Toast.makeText(this, "حذف شد", Toast.LENGTH_SHORT).show()
                    ensureAccountThenLoad()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun refreshMessages() {
        if (token.isBlank() && address.isNotBlank()) {
            val acc = TempMailStore.getAccount(this, address)
            if (acc != null) {
                lifecycleScope.launch {
                    val t = withContext(Dispatchers.IO) {
                        try { TempMailApi.login(acc.address, acc.password, acc.apiBase.ifBlank { "https://api.mail.tm" }) } catch (_: Exception) { "" }
                    }
                    if (t.isNotBlank()) {
                        token = t
                        TempMailStore.save(this@TempMailActivity, acc.copy(token = t))
                        loadMessages()
                    } else {
                        binding.swipe.isRefreshing = false
                        Toast.makeText(this@TempMailActivity, "توکن منقضی — دوباره بسازید", Toast.LENGTH_SHORT).show()
                    }
                }
                return
            }
        }
        loadMessages()
    }

    private fun loadMessages() {
        if (token.isBlank()) {
            binding.swipe.isRefreshing = false
            return
        }
        binding.swipe.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(TempMailApi.listMessages(token, apiBase))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            binding.swipe.isRefreshing = false
            result.onSuccess { list ->
                val mapped = list.mapIndexed { idx, t ->
                    MailMessage(
                        uid = idx.toLong(),
                        from = t.from,
                        subject = t.subject,
                        date = t.createdAt.take(19).replace("T", " "),
                        preview = t.intro,
                        body = t.body.ifBlank { t.intro },
                        codes = t.codes,
                        unread = true
                    )
                }
                // store ids for open
                tempIdMap.clear()
                list.forEachIndexed { idx, t -> tempIdMap[idx.toLong()] = t.id }
                adapter.submit(mapped)
                binding.empty.visibility = if (mapped.isEmpty()) View.VISIBLE else View.GONE
                if (mapped.isNotEmpty()) {
                    binding.list.announceForAccessibility("${mapped.size} نامه موقت")
                }
            }.onFailure { e ->
                Toast.makeText(this@TempMailActivity, "خطا: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val tempIdMap = HashMap<Long, String>()

    private fun openTempMessage(uidKey: String) {
        val id = tempIdMap[uidKey.toLongOrNull() ?: return] ?: return
        binding.swipe.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { Result.success(TempMailApi.getMessage(token, id, apiBase)) } catch (e: Exception) { Result.failure(e) }
            }
            binding.swipe.isRefreshing = false
            result.onSuccess { t ->
                val msg = MailMessage(
                    uid = 0,
                    from = t.from,
                    subject = t.subject,
                    date = t.createdAt,
                    preview = t.intro,
                    body = t.body,
                    codes = t.codes,
                    unread = false
                )
                startActivity(Intent(this@TempMailActivity, ReadActivity::class.java).putExtra("message", msg))
            }.onFailure {
                Toast.makeText(this@TempMailActivity, "باز کردن نامه ناموفق", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(15000)
                if (token.isNotBlank()) loadMessages()
            }
        }
    }
}
