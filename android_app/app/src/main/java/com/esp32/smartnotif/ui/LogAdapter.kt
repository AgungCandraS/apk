package com.esp32.smartnotif.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.esp32.smartnotif.R
import com.esp32.smartnotif.model.NotifLogItem

class LogAdapter(private val items: MutableList<NotifLogItem> = mutableListOf()) :
    RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAppBadge: TextView = view.findViewById(R.id.tvAppBadge)
        val tvSender: TextView = view.findViewById(R.id.tvSender)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvDeliveryStatus: TextView = view.findViewById(R.id.tvDeliveryStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notif_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvAppBadge.text = item.appCode
        holder.tvSender.text = item.sender
        holder.tvTime.text = item.timestamp
        holder.tvMessage.text = item.message
        holder.tvDeliveryStatus.text = item.deliveryStatus
        holder.tvDeliveryStatus.visibility = if (item.deliveryStatus.isBlank()) View.GONE else View.VISIBLE
    }

    override fun getItemCount(): Int = items.size

    fun addItem(item: NotifLogItem) {
        items.add(0, item)
        notifyItemInserted(0)
        if (items.size > 50) {
            items.removeAt(items.size - 1)
            notifyItemRemoved(50)
        }
    }

    val latestItem: NotifLogItem? get() = items.firstOrNull()

    fun updateDelivery(payload: String, status: String): Boolean {
        // BLE writes are FIFO; repeated payloads must update the oldest pending entry.
        val index = items.indexOfLast { it.pendingPayload == payload }
        if (index < 0) return false
        items[index] = items[index].copy(deliveryStatus = status, pendingPayload = null)
        notifyItemChanged(index)
        return true
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }
}
