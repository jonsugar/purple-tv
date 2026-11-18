package com.purpletv.home

import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import com.purpletv.R
import com.purpletv.twitch.FollowKind

internal fun showReorderPins(anchor: View, pins: PinStore, account: String, kind: FollowKind) {
    val builder = AlertDialog.Builder(anchor.context, R.style.PurpleDialog)
    val body = LayoutInflater.from(builder.context).inflate(R.layout.dialog_reorder_pins, null)
    val list = body.findViewById<ListView>(R.id.reorder_list)
    val up = body.findViewById<View>(R.id.reorder_up)
    val down = body.findViewById<View>(R.id.reorder_down)
    val names = ArrayAdapter<String>(builder.context, android.R.layout.simple_list_item_single_choice)
    list.adapter = names
    var entries = pins.list(account, kind)
    var selectedId = entries.firstOrNull()?.id

    fun updateControls(index: Int) {
        list.setItemChecked(index, true)
        up.isEnabled = index > 0
        down.isEnabled = index < entries.lastIndex
        up.isFocusable = up.isEnabled
        down.isFocusable = down.isEnabled
        up.alpha = if (up.isEnabled) 1f else 0.5f
        down.alpha = if (down.isEnabled) 1f else 0.5f
        list.nextFocusDownId = when {
            up.isEnabled -> up.id
            down.isEnabled -> down.id
            else -> android.R.id.button1
        }
    }

    fun render() {
        names.setNotifyOnChange(false)
        names.clear()
        names.addAll(entries.map { it.name })
        names.notifyDataSetChanged()
        list.clearChoices()
        val index = entries.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        updateControls(index)
        list.setSelection(index)
    }

    list.setOnItemClickListener { _, _, position, _ ->
        selectedId = entries[position].id
        updateControls(position)
        if (!up.requestFocus()) down.requestFocus()
    }

    fun move(direction: Int, used: View, other: View) {
        val id = selectedId ?: return
        val hadFocus = used.hasFocus()
        pins.move(account, kind, id, direction)
        entries = pins.list(account, kind)
        render()
        if (hadFocus && !used.isEnabled && !other.requestFocus()) list.requestFocus()
    }
    up.setOnClickListener { move(-1, up, down) }
    down.setOnClickListener { move(1, down, up) }

    val dialog = builder.setTitle(R.string.reorder_pins)
        .setView(body)
        .setPositiveButton(R.string.close, null)
        .create()
    dialog.setOnShowListener {
        list.requestFocus()
        render()
    }
    dialog.setOnDismissListener { anchor.requestFocus() }
    dialog.show()
}
