package com.example.bluewifi.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.example.bluewifi.databinding.ItemWifiBinding
import com.example.bluewifi.wifi.WifiItem

class WifiListAdapter(
    private val onItemClick: ((WifiItem) -> Unit)? = null
) : ListAdapter<WifiItem, WifiListAdapter.WifiViewHolder>(DiffCallback) {

    var targetSsid: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class WifiViewHolder(val binding: ItemWifiBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WifiViewHolder {
        val binding = ItemWifiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WifiViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WifiViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.tvSsid.text = item.ssid

        val isTarget = !targetSsid.isNullOrEmpty() && item.ssid.equals(targetSsid, ignoreCase = true)
        holder.binding.tvTargetBadge.visibility = if (isTarget) View.VISIBLE else View.GONE

        val freqStr = if (item.is5Ghz) "5G" else "2.4G"
        val capSummary = when {
            item.capabilities.contains("WPA3", ignoreCase = true) -> "WPA3"
            item.capabilities.contains("WPA2", ignoreCase = true) -> "WPA2"
            item.capabilities.contains("WPA", ignoreCase = true) -> "WPA"
            item.capabilities.contains("WEP", ignoreCase = true) -> "WEP"
            else -> "开放"
        }

        holder.binding.tvDetails.text = "${item.bssid} | ${item.level} dBm | $freqStr | $capSummary"
        holder.binding.tvLevelPercent.text = "${item.signalPercent}%"

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(item)
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WifiItem>() {
        override fun areItemsTheSame(oldItem: WifiItem, newItem: WifiItem): Boolean {
            return oldItem.bssid == newItem.bssid
        }

        override fun areContentsTheSame(oldItem: WifiItem, newItem: WifiItem): Boolean {
            return oldItem == newItem
        }
    }
}
