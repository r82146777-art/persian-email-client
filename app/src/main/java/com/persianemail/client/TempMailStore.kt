package com.persianemail.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TempMailStore {
    private const val PREFS = "temp_mail_store"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_ACTIVE = "active_address"

    fun list(context: Context): List<TempMailApi.TempAccount> {
        val raw = prefs(context).getString(KEY_ACCOUNTS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val out = ArrayList<TempMailApi.TempAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                TempMailApi.TempAccount(
                    address = o.getString("address"),
                    password = o.optString("password", ""),
                    token = o.optString("token", ""),
                    apiBase = o.optString("apiBase", ""),
                    provider = o.optString("provider", "mailtm")
                )
            )
        }
        return out
    }

    fun save(context: Context, account: TempMailApi.TempAccount) {
        val list = list(context).toMutableList()
        val idx = list.indexOfFirst { it.address.equals(account.address, true) }
        if (idx >= 0) list[idx] = account else list.add(0, account)
        writeAll(context, list)
        setActive(context, account.address)
    }

    fun remove(context: Context, address: String) {
        val list = list(context).filterNot { it.address.equals(address, true) }
        writeAll(context, list)
        if (getActive(context)?.equals(address, true) == true) {
            prefs(context).edit().putString(KEY_ACTIVE, list.firstOrNull()?.address ?: "").apply()
        }
    }

    fun getActive(context: Context): String? {
        val a = prefs(context).getString(KEY_ACTIVE, null)
        return if (a.isNullOrBlank()) list(context).firstOrNull()?.address else a
    }

    fun setActive(context: Context, address: String) {
        prefs(context).edit().putString(KEY_ACTIVE, address).apply()
    }

    fun getAccount(context: Context, address: String): TempMailApi.TempAccount? =
        list(context).firstOrNull { it.address.equals(address, true) }

    private fun writeAll(context: Context, list: List<TempMailApi.TempAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("address", a.address)
                    .put("password", a.password)
                    .put("token", a.token)
                    .put("apiBase", a.apiBase)
                    .put("provider", a.provider)
            )
        }
        prefs(context).edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
