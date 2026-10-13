package com.persianemail.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
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
    private var current: TempMailApi.TempAccount? = null
    private var pollJob: Job? = null
    private val tempIdMap = HashMap<Long, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTempMailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = EmailAdapter { msg -> openTempMessage(msg.uid) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnCopy.setOnClickListener { copyAddress() }
        binding.btnCreate.setOnClickListener { createNew() }
        binding.btnCreateAtomic.setOnClickListener { createNew() }
        binding.btnSwitch.setOnClickListener { showManage() }
        binding.swipe.setOnRefreshListener { loadMessages() }

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
            loadMessages()
        } else {
            createNew()
        }
    }

    private fun bindAccount(acc: TempMailApi.TempAccount) {
        current = acc
        binding.activeAddress.text = acc.address
        val providerLabel = when (acc.provider) {
            "tempmaillol" -> "TempMail.lol"
            "guerrilla" -> "Guerrilla"
            "tempmailio" -> "Temp-Mail.io"
            else -> "Mail.tm"
        }
        binding.activeAddress.contentDescription = "آدرس فعال ${acc.address} از $providerLabel"
        supportActionBar?.subtitle = providerLabel
    }

    private fun createNew() {
        binding.swipe.isRefreshing = true
        binding.activeAddress.text = "در حال ساخت ایمیل موقت…"
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
                Toast.makeText(this@TempMailActivity, "ایمیل جدید: ${acc.address}", Toast.LENGTH_LONG).show()
                binding.activeAddress.announceForAccessibility("ایمیل جدید ساخته شد ${acc.address}")
                loadMessages()
            }.onFailure { e ->
                val msg = e.message ?: "ساخت ایمیل ناموفق"
                binding.activeAddress.text = "خطا — دوباره تلاش کنید"
                Toast.makeText(this@TempMailActivity, msg, Toast.LENGTH_LONG).show()
                binding.activeAddress.announceForAccessibility(msg)
            }
        }
    }

    private fun copyAddress() {
        val address = current?.address.orEmpty()
        if (address.isBlank()) {
            Toast.makeText(this, "آدرسی نیست", Toast.LENGTH_SHORT).show()
            return
        }
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
        val labels = accounts.map {
            val p = when (it.provider) {
                "tempmaillol" -> "Temp"
                "guerrilla" -> "Guerrilla"
                "tempmailio" -> "IO"
                else -> "Mail.tm"
            }
            "$p — ${it.address}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("ایمیل‌های ذخیره‌شده")
            .setItems(labels) { _, which ->
                val acc = accounts[which]
                TempMailStore.setActive(this, acc.address)
                bindAccount(acc)
                loadMessages()
            }
            .setNeutralButton("حذف فعال") { _, _ ->
                current?.address?.let {
                    TempMailStore.remove(this, it)
                    Toast.makeText(this, "حذف شد", Toast.LENGTH_SHORT).show()
                    ensureAccountThenLoad()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun loadMessages() {
        val acc = current
        if (acc == null) {
            binding.swipe.isRefreshing = false
            return
        }
        binding.swipe.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(TempMailApi.listMessages(acc))
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
                tempIdMap.clear()
                list.forEachIndexed { idx, t -> tempIdMap[idx.toLong()] = t.id }
                adapter.submit(mapped)
                binding.empty.visibility = if (mapped.isEmpty()) View.VISIBLE else View.GONE
                if (mapped.isNotEmpty()) {
                    binding.list.announceForAccessibility("${mapped.size} نامه موقت")
                }
            }.onFailure { e ->
                Toast.makeText(this@TempMailActivity, "دریافت نامه: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openTempMessage(uid: Long) {
        val id = tempIdMap[uid] ?: return
        val acc = current ?: return
        binding.swipe.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(TempMailApi.getMessage(acc, id))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            binding.swipe.isRefreshing = false
            result.onSuccess { t ->
                val msg = MailMessage(
                    uid = 0,
                    from = t.from,
                    subject = t.subject,
                    date = t.createdAt,
                    preview = t.intro,
                    body = t.body.ifBlank { t.intro },
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
                delay(12000)
                if (current != null) loadMessages()
            }
        }
    }
}
