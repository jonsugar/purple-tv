package com.purpletv.ui

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.purpletv.R
import com.purpletv.twitch.NotLoggedInException
import com.purpletv.twitch.Page
import com.purpletv.twitch.Recording
import kotlinx.coroutines.CoroutineScope

/** A paginated horizontal row; resetting cancels responses from an old clip filter. */
class RecordingRow(
    private val root: View,
    scope: CoroutineScope,
    private val emptyText: Int,
    fetch: suspend (String?) -> Page<Recording>,
    onAuthRequired: (NotLoggedInException) -> Unit,
) {
    private val list: CardRow = root.findViewById(R.id.recording_row_list)
    private val status: TextView = root.findViewById(R.id.recording_row_status)
    private val retry: View = root.findViewById(R.id.recording_row_retry)
    private val adapter: RecordingAdapter = RecordingAdapter({ Nav.openRecording(root.context, it) }) { if (!pager.hasFailed) pager.loadMore() }
    private val pager: Pager<Recording> = Pager(scope, { recording: Recording -> recording.id }, fetch, onAuthRequired) { items, failed ->
        adapter.submitList(items)
        status.setText(if (failed) R.string.row_error else emptyText)
        status.visibility = if (items.isEmpty() || failed) View.VISIBLE else View.GONE
        val hadFocus = retry.hasFocus()
        retry.visibility = if (failed) View.VISIBLE else View.GONE
        if (hadFocus && !failed) list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
    }
    init {
        list.layoutManager = LinearLayoutManager(root.context, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = adapter
        list.itemAnimator = null
        retry.setOnClickListener { pager.loadMore() }
    }

    fun load() { pager.loadMore() }

    fun reset() {
        pager.reset()
        adapter.submitList(emptyList())
        list.rememberedCard = 0
        list.scrollToPosition(0)
        status.setText(R.string.loading)
        status.visibility = View.VISIBLE
        retry.visibility = View.GONE
    }
}
