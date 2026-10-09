package com.persianemail.client

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.persianemail.client.databinding.ItemEmailBinding

class EmailAdapter(
    private val onClick: (MailMessage) -> Unit
) : RecyclerView.Adapter<EmailAdapter.VH>() {

    private val items = ArrayList<MailMessage>()

    fun submit(list: List<MailMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemEmailBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val b: ItemEmailBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(m: MailMessage) {
            b.from.text = m.from
            b.subject.text = m.subject
            b.preview.text = m.preview
            b.date.text = m.date
            b.subject.setTypeface(null, if (m.unread) Typeface.BOLD else Typeface.NORMAL)
            if (m.codes.isNotEmpty()) {
                b.otpBadge.visibility = View.VISIBLE
                b.otpBadge.text = "کد: ${m.codes.first()}"
            } else {
                b.otpBadge.visibility = View.GONE
            }
            val a11y = buildString {
                append(if (m.unread) "خوانده‌نشده. " else "")
                append("از ${m.from}. موضوع ${m.subject}. ")
                if (m.codes.isNotEmpty()) append("حاوی کد تأیید ${m.codes.joinToString()}. ")
                append(m.date)
            }
            b.root.contentDescription = a11y
            b.root.setOnClickListener { onClick(m) }
        }
    }
}
