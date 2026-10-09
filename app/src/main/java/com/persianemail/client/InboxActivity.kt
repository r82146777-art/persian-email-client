package com.persianemail.client

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.persianemail.client.databinding.ActivityInboxBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InboxActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInboxBinding
    private lateinit var adapter: EmailAdapter
    private var account: MailAccount? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInboxBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.inbox)

        account = SessionStore.load(this)
        if (account == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        adapter = EmailAdapter { msg ->
            val i = Intent(this, ReadActivity::class.java)
            i.putExtra("uid", msg.uid)
            i.putExtra("message", msg)
            startActivity(i)
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        binding.swipe.setOnRefreshListener { load() }
        binding.fabCompose.setOnClickListener {
            startActivity(Intent(this, ComposeActivity::class.java))
        }
        load()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.inbox_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> { load(); true }
            R.id.action_logout -> {
                SessionStore.clear(this)
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun load() {
        val acc = account ?: return
        binding.swipe.isRefreshing = true
        binding.empty.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(MailService.fetchInbox(acc))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            binding.swipe.isRefreshing = false
            result.onSuccess { list ->
                adapter.submit(list)
                binding.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.list.announceForAccessibility("${list.size} نامه بارگذاری شد")
            }.onFailure { e ->
                Toast.makeText(this@InboxActivity, "خطا: ${e.message}", Toast.LENGTH_LONG).show()
                binding.list.announceForAccessibility("خطا در دریافت نامه‌ها")
            }
        }
    }
}
