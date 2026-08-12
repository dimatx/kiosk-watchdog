package com.shymoose.wifiwatchdog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.shymoose.wifiwatchdog.databinding.ItemEventBinding

class EventAdapter : RecyclerView.Adapter<EventAdapter.Holder>() {

    private var items: List<LogEvent> = emptyList()

    fun submit(newItems: List<LogEvent>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(o: Int, n: Int) =
                items[o].timestamp == newItems[n].timestamp && items[o].message == newItems[n].message

            override fun areContentsTheSame(o: Int, n: Int) = items[o] == newItems[n]
        })
        items = newItems
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    class Holder(private val binding: ItemEventBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(event: LogEvent) {
            binding.time.text = event.formattedTime()
            binding.message.text = event.message
            val color = when (event.level) {
                EventLevel.ERROR -> R.color.status_bad
                EventLevel.WARN -> R.color.status_warn
                EventLevel.ACTION -> R.color.status_action
                EventLevel.INFO -> R.color.status_ok
            }
            binding.levelBar.setBackgroundColor(ContextCompat.getColor(binding.root.context, color))
        }
    }
}
