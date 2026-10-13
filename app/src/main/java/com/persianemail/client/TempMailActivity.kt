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
        binding.btnCreateAccount.setOnClickListener { createWithUsername() }
        binding.btnCreateRandom.setOnClickListener { createRandom() }
        binding.btnAtomicOfficial.setOnClickListener { openAtomicOfficial() }
        binding.btnSwitch.setOnClickListener { showManage() }
        binding.swipe.setOnRefreshListener { loadMessages() }

        loadDomains()
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

    private fun loadDomains() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    TempMailApi.listAvailableDomains()
                } catch (_: Exception) {
                    listOf("maxxspace.com", "guerrillamailblock.com")
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
            loadMessages()
        } else {
            binding.activeAddress.text = "نام کاربری بزنید و «ایجاد حساب» را بزنید"
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
        binding.activeAddress.contentDescription = "آدرس فعال ${acc.address}"
        supportActionBar?.subtitle = providerLabel
    }

    private fun createWithUsername() {
        val user = binding.usernameInput.text?.toString()?.trim().orEmpty()
        if (user.length < 3) {
            Toast.makeText(this, "نام کاربری حداقل ۳ حرف باشد", Toast.LENGTH_SHORT).show()
            binding.usernameInput.requestFocus()
            return
        }
        val domain = try {
            binding.domainSpinner.selectedItem?.toString()
        } catch (_: Exception) {
            null
        }
        binding.swipe.isRefreshing = true
        binding.activeAddress.text = "در حال ایجاد $user@…"
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
                Toast.makeText(this@TempMailActivity, "حساب ساخته شد: ${acc.address}", Toast.LENGTH_LONG).show()
                binding.activeAddress.announceForAccessibility("ایمیل ${acc.address} ساخته شد")
                loadMessages()
            }.onFailure { e ->
                val msg = e.message ?: "ساخت ناموفق"
                binding.activeAddress.text = "خطا در ایجاد"
                Toast.makeText(this@TempMailActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createRandom() {
        binding.swipe.isRefreshing = true
        binding.activeAddress.text = "در حال ساخت تصادفی…"
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
                Toast.makeText(this@TempMailActivity, "ایمیل: ${acc.address}", Toast.LENGTH_LONG).show()
                loadMessages()
            }.onFailure { e ->
                Toast.makeText(this@TempMailActivity, e.message ?: "خطا", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openAtomicOfficial() {
        startActivity(
            Intent(this, SignupActivity::class.java)
                .putExtra("provider_name", "Atomic Mail")
                .putExtra("signup_url", "https://atomicmail.io/app/auth/sign-up")
                .putExtra(
                    "signup_hint",
                    "نام و نام‌خانوادگی، سپس نام کاربری دلخواه (مثلا ali) تا آدرس ali@atomicmail.io ساخته شود. رمز بگذارید و Seed Phrase را ذخیره کنید. بدون شماره موبایل."
                )
        )
    }

    private fun copyAddress() {
        val address = current?.address.orEmpty()
        if (address.isBlank()) {
            Toast.makeText(this, "اول یک ایمیل بسازید", Toast.LENGTH_SHORT).show()
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
        val labels = accounts.map { it.address }.toTypedArray()
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
                    current = null
                    binding.activeAddress.text = "حذف شد — دوباره بسازید"
                    adapter.submit(emptyList())
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
