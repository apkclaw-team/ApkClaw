package com.apk.claw.android.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.apk.claw.android.R
import com.apk.claw.android.session.SessionChatMessage
import com.apk.claw.android.session.SessionMemoryManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionChatAdapter(context: Context) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var items: List<SessionChatMessage> = emptyList()

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): SessionChatMessage = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: inflater.inflate(R.layout.item_session_chat_message, parent, false)
        val holder = (view.tag as? ViewHolder) ?: ViewHolder(view).also { view.tag = it }
        val item = getItem(position)
        val isUser = item.role == SessionMemoryManager.ROLE_USER
        val isSystem = item.role == SessionMemoryManager.ROLE_SYSTEM

        holder.container.gravity = when {
            isUser -> Gravity.END
            isSystem -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.START
        }
        holder.tvRole.text = when (item.role) {
            SessionMemoryManager.ROLE_USER -> parent.context.getString(R.string.session_memory_role_user)
            SessionMemoryManager.ROLE_SYSTEM -> parent.context.getString(R.string.session_memory_role_system)
            else -> parent.context.getString(R.string.session_memory_role_assistant)
        }
        holder.tvMessage.text = item.content
        holder.tvTime.text = timeFormatter.format(Date(item.timestamp))
        holder.tvMessage.background = GradientDrawable().apply {
            cornerRadius = 28f
            setColor(
                when {
                    isUser -> Color.parseColor("#DDEFFF")
                    isSystem -> Color.parseColor("#F4F4F4")
                    else -> Color.parseColor("#FFFFFF")
                }
            )
            setStroke(2, Color.parseColor(if (isSystem) "#D8D8D8" else "#E7E7E7"))
        }
        return view
    }

    fun submitList(items: List<SessionChatMessage>) {
        this.items = items
        notifyDataSetChanged()
    }

    private class ViewHolder(view: View) {
        val container: LinearLayout = view.findViewById(R.id.layoutMessageContainer)
        val tvRole: TextView = view.findViewById(R.id.tvMessageRole)
        val tvMessage: TextView = view.findViewById(R.id.tvMessageContent)
        val tvTime: TextView = view.findViewById(R.id.tvMessageTime)
    }
}
