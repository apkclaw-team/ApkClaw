package com.apk.claw.android.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.apk.claw.android.R
import com.apk.claw.android.session.SessionMemory
import com.apk.claw.android.widget.KButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionListAdapter(
    context: Context,
    private val onOpen: (SessionMemory) -> Unit,
    private val onDelete: (SessionMemory) -> Unit
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private var items: List<SessionMemory> = emptyList()
    private var currentSessionId: String = ""

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): SessionMemory = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    @SuppressLint("SetTextI18n")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_session_entry, parent, false)
        val holder = (view.tag as? ViewHolder) ?: ViewHolder(view).also { view.tag = it }
        val item = getItem(position)
        holder.tvName.text = item.name
        holder.tvMeta.text = parent.context.getString(
            R.string.session_memory_updated_at,
            timeFormatter.format(Date(item.updatedAt))
        )
        holder.tvCurrent.visibility = if (item.id == currentSessionId) View.VISIBLE else View.GONE
        holder.btnOpen.setOnClickListener { onOpen(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }
        view.setOnClickListener { onOpen(item) }
        return view
    }

    fun submitList(items: List<SessionMemory>, currentSessionId: String) {
        this.items = items
        this.currentSessionId = currentSessionId
        notifyDataSetChanged()
    }

    private class ViewHolder(view: View) {
        val tvName: TextView = view.findViewById(R.id.tvSessionName)
        val tvMeta: TextView = view.findViewById(R.id.tvSessionMeta)
        val tvCurrent: TextView = view.findViewById(R.id.tvCurrentSession)
        val btnOpen: KButton = view.findViewById(R.id.btnOpenSession)
        val btnDelete: KButton = view.findViewById(R.id.btnDeleteSession)
    }
}
