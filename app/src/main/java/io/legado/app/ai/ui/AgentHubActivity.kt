package io.legado.app.ai.ui

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.ai.tool.AiPreset
import io.legado.app.ai.tool.ConfirmRequest
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.SimpleRecyclerAdapter
import kotlinx.android.synthetic.main.activity_agent_hub.*
import kotlinx.android.synthetic.main.item_agent_message.view.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.anko.sdk27.listeners.onClick

/**
 * AI Agent Hub 中心页：会话对话 + 工具卡片回流 + 写操作二次确认 + 上下文预设注入。
 */
class AgentHubActivity : BaseActivity(R.layout.activity_agent_hub) {

    private lateinit var adapter: MessageAdapter
    private lateinit var vm: AgentHubViewModel
    private var confirmingToken: String? = null
    private val uiJobs = ArrayList<Job>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        vm = AgentHubViewModel(readPreset())
        initView()
        initVm()
    }

    override fun onDestroy() {
        uiJobs.forEach { it.cancel() }
        uiJobs.clear()
        runCatching { vm.dispose() }
        super.onDestroy()
    }

    private fun readPreset(): AiPreset = intent?.let {
        AiPreset(
            bookName = it.getStringExtra("preset_book"),
            chapterTitle = it.getStringExtra("preset_chapter"),
            content = it.getStringExtra("preset_content"),
            sourceUrl = it.getStringExtra("preset_source_url"),
            searchKeyword = it.getStringExtra("preset_search")
        )
    } ?: AiPreset()

    private fun initView() {
        recycler_view.layoutManager = LinearLayoutManager(this)
        adapter = MessageAdapter(this)
        recycler_view.adapter = adapter

        btn_send.onClick { send() }
        et_input.setOnEditorActionListener { _, _, _ -> send(); true }
        btn_stop.onClick { vm.stop() }
    }

    private fun initVm() {
        vm.start(this)
        uiJobs += launch {
            while (isActive) {
                val list = vm.messages.value
                adapter.setItems(list)
                if (list.isNotEmpty()) {
                    recycler_view.scrollToPosition(list.size - 1)
                }
                kotlinx.coroutines.delay(300)
            }
        }
        uiJobs += launch {
            while (isActive) {
                tv_typing.visibility =
                    if (vm.typing.value) {
                        android.view.View.VISIBLE
                    } else {
                        android.view.View.GONE
                    }
                kotlinx.coroutines.delay(200)
            }
        }
        uiJobs += launch {
            while (isActive) {
                val req = vm.confirm.value
                if (req != null && confirmingToken != req.confirmToken) {
                    confirmingToken = req.confirmToken
                    showConfirm(req.confirmToken, req.proposal)
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    private fun send() {
        val text = et_input.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        et_input.setText("")
        vm.send(text, this)
    }

    private fun showConfirm(token: String, proposal: Map<String, Any>) {
        val body = proposal.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        AlertDialog.Builder(this)
            .setTitle("等待工具确认")
            .setMessage(body)
            .setPositiveButton("同意") { _, _ -> vm.approve(token, true) }
            .setNegativeButton("拒绝") { _, _ -> vm.approve(token, false) }
            .setOnDismissListener { confirmingToken = null }
            .show()
    }

    inner class MessageAdapter(context: Context) :
        SimpleRecyclerAdapter<ChatRow>(context, R.layout.item_agent_message) {

        override fun convert(holder: ItemViewHolder, item: ChatRow, payloads: MutableList<Any>) {
            holder.itemView.apply {
                tv_message.text = if (item.role == "user") {
                    "我：${item.content}"
                } else {
                    "助手：${item.content}"
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder) {
        }
    }
}