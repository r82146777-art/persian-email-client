package com.persianemail.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
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
    private var domains: List<String> = emptyList()

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
        binding.btnRefresh.setOnClickListener { loadMessages(true) }
        binding.btnCreateRandom.setOnClickListener { createRandom() }
        binding.btnCreateAccount.setOnClickListener { createWithUsername() }
        binding.btnSwitch.setOnClickListener { showManage() }
        binding.swipe.setOnRefreshListener { loadMessages(true) }

        loadDomains()
        ensureAccountThenLoad()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        if (current != null) loadMessages(false)
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }

    private fun loadDomains() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    TempMailApi.listAvailableDomains()
                } catch (_: Exception) {
                    listOf("maxxspace.com")
                }
            }
            domains = list
            binding.domainSpinner.adapter =
                ArrayAdapter(this@TempMailActivity, android.R.layout.simple_spinner_dropdown_item, list)
        }
    }

    private fun ensureAccountThenLoad() {
        val existing = TempMailStore.getActive(this)?.let { TempMailStore.getAccount(this, it) }
        if (existing != null) {
            bindAccount(existing)
            loadMessages(true)
        } else {
            binding.activeAddress.text = "هنوز ایمیلی نیست — «ایمیل جدید» را بزنید"
            binding.statusText.text = "آماده برای ساخت"
            binding.empty.visibility = View.VISIBLE
        }
    }

    private fun bindAccount(acc: TempMailApi.TempAccount) {
        current = acc
        binding.activeAddress.text = acc.address
        val label = if (acc.provider == "guerrilla") "Guerrilla" else "Mail.tm"
        supportActionBar?.subtitle = label
        binding.activeAddress.contentDescription = "آدرس فعال ${acc.address}"
        binding.statusText.text = "ذخیره شد — $label"
    }

    private fun createRandom() {
        binding.statusText.text = "در حال ساخت ایمیل…"
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
                tempIdMap.clear()
                binding.empty.visibility = View.VISIBLE
                Toast.makeText(this@TempMailActivity, "ساخته شد: ${acc.address}", Toast.LENGTH_LONG).show()
                binding.activeAddress.announceForAccessibility("ایمیل ${acc.address} ساخته و ذخیره شد")
                loadMessages(false)
            }.onFailure { e ->
                binding.statusText.text = "خطا در ساخت"
                Toast.makeText(this@TempMailActivity, e.message ?: "خطا", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createWithUsername() {
        val user = binding.usernameInput.text?.toString()?.trim().orEmpty()
        if (user.length < 3) {
            Toast.makeText(this, "نام کاربری حداقل ۳ حرف", Toast.LENGTH_SHORT).show()
            return
        }
        val domain = try {
            binding.domainSpinner.selectedItem?.toString()
        } catch (_: Exception) {
            null
        }
        binding.statusText.text = "در حال ایجاد $user@…"
        binding.swipe.isRefreshing = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(TempMailApi.createWithUsername(user, domain))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            binding.swipe.isRefreshing = false
            result.onSuccess { acc ->
                TempMailStore.save(this@TempMailActivity, acc)
                bindAccount(acc)
                adapter.submit(emptyList())
                tempIdMap.clear()
                binding.empty.visibility = View.VISIBLE
                Toast.makeText(this@TempMailActivity, "ساخته شد: ${acc.address}", Toast.LENGTH_LONG).show()
                loadMessages(false)
            }.onFailure { e ->
                binding.statusText.text = "خطا"
                Toast.makeText(this@TempMailActivity, e.message ?: "خطا", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyAddress() {
        val address = current?.address.orEmpty()
        if (address.isBlank()) {
            Toast.makeText(this, "اول ایمیل بسازید", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("temp-email", address))
        Toast.makeText(this, "کپی شد: $address", Toast.LENGTH_SHORT).show()
    }

    private fun showManage() {
        val accounts = TempMailStore.list(this)
        if (accounts.isEmpty()) {
            Toast.makeText(this, "آدرس ذخیره‌شده‌ای نیست", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = accounts.map { it.address }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("آدرس‌های ذخیره‌شده")
            .setItems(labels) { _, which ->
                val acc = accounts[which]
                TempMailStore.setActive(this, acc.address)
                bindAccount(acc)
                loadMessages(true)
            }
            .setNeutralButton("حذف فعال") { _, _ ->
                current?.address?.let {
                    TempMailStore.remove(this, it)
                    current = null
                    binding.activeAddress.text = "حذف شد"
                    adapter.submit(emptyList())
                    binding.empty.visibility = View.VISIBLE
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun loadMessages(showToast: Boolean) {
        val acc = current
        if (acc == null) {
            binding.swipe.isRefreshing = false
            return
        }
        binding.swipe.isRefreshing = true
        binding.statusText.text = "در حال دریافت نامه…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(TempMailApi.listMessages(acc))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            binding.swipe.isRefreshing = false
            result.onSuccess { pair ->
                val fresh = pair.first
                val list = pair.second
                // persist refreshed token
                if (fresh.token != acc.token || fresh.apiBase != acc.apiBase) {
                    TempMailStore.save(this@TempMailActivity, fresh)
                    current = fresh
                }
                val mapped = list.mapIndexed { idx, t ->
                    MailMessage(
                        uid = idx.toLong(),
                        from = t.from.ifBlank { "(ناشناس)" },
                        subject = t.subject.ifBlank { "(بدون موضوع)" },
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
                binding.statusText.text = if (mapped.isEmpty()) {
                    "صندوق خالی — منتظر نامه"
                } else {
                    "${mapped.size} نامه"
                }
                if (mapped.isNotEmpty()) {
                    binding.list.announceForAccessibility("${mapped.size} نامه")
                }
            }.onFailure { e ->
                binding.statusText.text = "خطا در دریافت"
                if (showToast) {
                    Toast.makeText(this@TempMailActivity, "دریافت: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openTempMessage(uid: Long) {
        val id = tempIdMap[uid] ?: return
        val acc = current ?: return
        binding.swipe.isRefreshing = true
        binding.statusText.text = "باز کردن نامه…"
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
                delay(8000)
                if (current != null) loadMessages(false)
            }
        }
    }
}
