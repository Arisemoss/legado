package io.legado.app.ai.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.ai.bridge.AppNav
import io.legado.app.base.BaseActivity
import io.legado.app.ai.model.ToolEvent
import io.legado.app.ai.tool.AiPreset
import io.legado.app.base.adapter.CommonRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.ItemViewDelegate
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigViewModel
import io.legado.app.ui.main.MainActivity
import kotlinx.android.synthetic.main.activity_agent_hub.*
import kotlinx.android.synthetic.main.ai_item_confirm.view.*
import kotlinx.android.synthetic.main.ai_item_error.view.*
import kotlinx.android.synthetic.main.ai_item_msg_ai.view.*
import kotlinx.android.synthetic.main.ai_item_msg_user.view.*
import kotlinx.android.synthetic.main.ai_item_session.view.*
import kotlinx.android.synthetic.main.ai_item_tool.view.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.anko.sdk27.listeners.onClick
import org.jetbrains.anko.startActivity

/**
 * AI Agent Hub 中心页：气泡对话 + 实时工具卡片 + 写操作内联二次确认 +
 * 多会话管理 + 上下文预设注入。
 */
class AgentHubActivity : BaseActivity(R.layout.activity_agent_hub) {

    companion object {
        /** 指定打开 MainActivity 后切换到的 tab(index)，配合 AppNav.ToBookshelf 使用 */
        const val EXTRA_SELECT_TAB = "agent_select_tab"

        /** 流式打字机临时气泡的行 key（最终回答落地后该行被移除） */
        private const val ROW_STREAMING = "__streaming__"

        private const val VT_USER = 0
        private const val VT_AI = 1
        private const val VT_TOOL = 2
        private const val VT_ERROR = 3
        private const val VT_CONFIRM = 4
    }

    private lateinit var adapter: ChatAdapter
    private lateinit var vm: AgentHubViewModel
    private val uiJobs = ArrayList<Job>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        vm = AgentHubViewModel(readPreset())
        adapter = ChatAdapter(this)
        initView()
        initVm()
        uiJobs += launch { vm.init() }
    }

    override fun onResume() {
        super.onResume()
        // 配置可能在设置页被修改，回来时热更新并刷新状态栏
        vm.refreshStatusLine()
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

    // ---------- 视图 ----------

    private fun initView() {
        recycler_view.layoutManager = LinearLayoutManager(this)
        recycler_view.adapter = adapter

        btn_send.onClick {
            val text = et_input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@onClick
            et_input.setText("")
            vm.send(text)
        }
        btn_stop.onClick { vm.stop() }

        btn_config.onClick {
            startActivity<ConfigActivity>(Pair("configType", ConfigViewModel.TYPE_AI_CONFIG))
        }
        btn_new_session.onClick {
            uiJobs += launch { runCatching { vm.newSession() } }
            toast("已新建会话")
        }
        btn_sessions.onClick { showSessionDialog() }
        btn_logs.onClick { startActivity<AiLogActivity>() }

        chip_summarize.onClick { fillInput("帮我总结当前正在读的这一章") }
        chip_find_book.onClick { fillInput("帮我在书源里找《诡秘之主》，并加入书架") }
        chip_characters.onClick { fillInput("分析一下当前这本书的主要人物关系") }
        chip_source.onClick { fillInput("检测我的书源哪些失效了，给出诊断") }
        chip_shelf.onClick { fillInput("看看我书架里有哪些书？") }
    }

    private fun fillInput(text: String) {
        et_input.setText(text)
        et_input.setSelection(text.length)
        et_input.requestFocus()
    }

    // ---------- 状态订阅 ----------

    private fun initVm() {
        vm.attach(this)

        // 状态刷新循环：仅在数据真正变化时重绘（修复原先每 150ms 全列表
        // notifyDataSetChanged 导致的闪烁/跳动/耗电）；流式增量文本合成为
        // 末尾的临时打字机气泡，仅就地更新该行。
        uiJobs += launch {
            var lastRendered: List<ChatRow>? = null
            var lastBusy: Boolean? = null
            while (isActive) {
                val base = vm.messages.value
                val partial = vm.currentPartial()
                val list = if (partial != null) {
                    base + ChatRow.Msg(ROW_STREAMING, "assistant", partial)
                } else base

                val isEmpty = list.isEmpty()
                box_empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
                recycler_view.visibility = if (isEmpty) View.GONE else View.VISIBLE
                val busyNow = vm.busy.value
                chips_scroll.visibility =
                    if (!busyNow || isEmpty) View.VISIBLE else View.GONE
                // 首个 token 到达前显示"思考中"，出字后由打字机气泡接管
                typing_bar.visibility =
                    if (vm.typing.value && partial == null) View.VISIBLE else View.GONE
                if (busyNow != lastBusy) {
                    btn_send.visibility = if (busyNow) View.GONE else View.VISIBLE
                    btn_stop.visibility = if (busyNow) View.VISIBLE else View.GONE
                    lastBusy = busyNow
                }
                tv_subtitle.text = vm.statusLine.value

                renderIfChanged(list, lastRendered)
                lastRendered = list
                kotlinx.coroutines.delay(if (partial != null) 90L else 150L)
            }
        }
        uiJobs += launch {
            while (isActive) {
                val nav = vm.navigation.value
                if (nav != null) {
                    vm.navigation.value = null // 消费掉，避免重复跳转
                    when (nav) {
                        is AppNav.OpenBook -> openReader(nav)
                        is AppNav.GlobalSearch -> openSearch(nav)
                        AppNav.ToBookshelf -> openBookshelf()
                    }
                }
                kotlinx.coroutines.delay(150)
            }
        }
    }

    /**
     * 差异化渲染：
     * - 引用相等 → 跳过（StateFlow 未变时不触发任何 notify）；
     * - 仅末行内容变化（流式打字机 / 决策态确认卡）→ notifyItemChanged 单行；
     * - 其余情况才全量 setItems。
     */
    private fun renderIfChanged(list: List<ChatRow>, last: List<ChatRow>?) {
        if (list === last) return
        if (last != null && list.size == last.size) {
            var diffIdx = -1
            for (i in list.indices) {
                if (list[i] != last[i]) {
                    if (diffIdx >= 0) { diffIdx = Int.MIN_VALUE; break }
                    diffIdx = i
                }
            }
            if (diffIdx == -1) return // 内容完全一致，无需重绘
            if (diffIdx != Int.MIN_VALUE) {
                adapter.setItem(diffIdx, list[diffIdx])
                if (diffIdx >= list.size - 2 && shouldPinBottom()) {
                    recycler_view.scrollToPosition(list.size - 1)
                }
                return
            }
        }
        adapter.setItems(list)
        if (list.isNotEmpty()) recycler_view.scrollToPosition(list.size - 1)
    }

    /** 用户是否停留在列表底部附近（决定流式更新时要不要跟随滚动） */
    private fun shouldPinBottom(): Boolean {
        if (adapter.itemCount == 0) return true
        val lm = recycler_view.layoutManager as? LinearLayoutManager ?: return true
        val lastVisible = lm.findLastCompletelyVisibleItemPosition()
        return lastVisible == RecyclerView.NO_POSITION || lastVisible >= adapter.itemCount - 2
    }

    private fun openReader(nav: AppNav.OpenBook) {
        val url = nav.bookUrl
        if (url.isNullOrBlank()) {
            toast("未定位到《${nav.bookName}》，可能未加入书架")
            return
        }
        startActivity<ReadBookActivity>(Pair("bookUrl", url), Pair("inBookshelf", true))
    }

    private fun openBookshelf() {
        Intent(this, MainActivity::class.java).let {
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            it.putExtra(EXTRA_SELECT_TAB, 0) // 书架 tab
            startActivity(it)
        }
    }

    private fun openSearch(nav: AppNav.GlobalSearch) {
        startActivity<SearchActivity>(Pair("key", nav.keyword))
    }

    // ---------- 会话管理弹窗 ----------

    private fun showSessionDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ai_sessions, null)
        val listView = dialogView.findViewById<ListView>(R.id.list_sessions)
        val listAdapter = object : BaseAdapter() {
            override fun getCount(): Int = vm.sessions.value.size
            override fun getItem(position: Int) = vm.sessions.value[position]
            override fun getItemId(position: Int) = getItem(position).id
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: layoutInflater
                    .inflate(R.layout.ai_item_session, parent, false)
                val s = getItem(position)
                v.tv_session_title.text =
                    if (s.id == vm.sessionId.value) "● ${s.title}" else s.title
                v.tv_session_time.text = vm.formatTime(s.updatedAt)
                v.btn_session_delete.onClick {
                    AlertDialog.Builder(this@AgentHubActivity)
                        .setMessage("确定删除会话「${s.title}」吗？")
                        .setPositiveButton("删除") { _, _ ->
                            uiJobs += launch { runCatching { vm.deleteSession(s.id) } }
                            toast("会话已删除")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                return v
            }
        }
        listView.adapter = listAdapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("会话记录")
            .setView(dialogView)
            .setPositiveButton("＋ 新建") { _, _ ->
                uiJobs += launch { runCatching { vm.newSession() } }
            }
            .setNeutralButton("清空当前消息") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("确定清空当前会话的全部消息吗？")
                    .setPositiveButton("清空") { _, _ ->
                        uiJobs += launch { runCatching { vm.clearCurrentMessages() } }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()

        listView.setOnItemClickListener { _, _, position, _ ->
            val target = vm.sessions.value.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            uiJobs += launch { runCatching { vm.switchTo(target.id) } }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- 消息多类型 Adapter ----------

    inner class ChatAdapter(context: Context) : CommonRecyclerAdapter<ChatRow>(context) {

        init {
            addItemViewDelegate(
                VT_USER,
                object : ItemViewDelegate<ChatRow>(context, R.layout.ai_item_msg_user) {
                    override fun convert(holder: ItemViewHolder, item: ChatRow, payloads: MutableList<Any>) {
                        holder.itemView.tv_user_text.text = (item as ChatRow.Msg).content
                    }

                    override fun registerListener(holder: ItemViewHolder) {}
                })
            addItemViewDelegate(
                VT_AI,
                object : ItemViewDelegate<ChatRow>(context, R.layout.ai_item_msg_ai) {
                    override fun convert(holder: ItemViewHolder, item: ChatRow, payloads: MutableList<Any>) {
                        holder.itemView.tv_ai_text.text = (item as ChatRow.Msg).content
                    }

                    override fun registerListener(holder: ItemViewHolder) {}
                })
            addItemViewDelegate(
                VT_TOOL,
                object : ItemViewDelegate<ChatRow>(context, R.layout.ai_item_tool) {
                    override fun convert(holder: ItemViewHolder, item: ChatRow, payloads: MutableList<Any>) {
                        val card = item as ChatRow.ToolCard
                        val v = holder.itemView
                        v.tv_tool_name.text = card.name
                        v.tv_tool_args.text = card.argsPreview
                        v.tv_tool_args.visibility =
                            if (card.argsPreview.isBlank()) View.GONE else View.VISIBLE
                        if (card.detail.isNullOrBlank()) {
                            v.tv_tool_detail.visibility = View.GONE
                        } else {
                            v.tv_tool_detail.visibility = View.VISIBLE
                            v.tv_tool_detail.text = card.detail
                        }
                        when (card.phase) {
                            ToolEvent.PHASE_RUNNING -> {
                                v.tv_tool_state_icon.text = "⏳"
                                v.tv_tool_state.text = "执行中"
                                v.tv_tool_state.setTextColor(Color.parseColor("#8A94A6"))
                            }
                            ToolEvent.PHASE_RESULT -> {
                                v.tv_tool_state_icon.text = "✅"
                                v.tv_tool_state.text = formatElapsed(card.elapsedMs)
                                v.tv_tool_state.setTextColor(context.getColorCompat(R.color.ai_ok_text))
                            }
                            ToolEvent.PHASE_CONFIRM -> {
                                v.tv_tool_state_icon.text = "🔐"
                                v.tv_tool_state.text = "待确认"
                                v.tv_tool_state.setTextColor(Color.parseColor("#E08E00"))
                            }
                            ToolEvent.PHASE_APPROVED -> {
                                v.tv_tool_state_icon.text = "✍️"
                                v.tv_tool_state.text = formatElapsed(card.elapsedMs)
                                v.tv_tool_state.setTextColor(context.getColorCompat(R.color.ai_ok_text))
                            }
                            ToolEvent.PHASE_DENIED -> {
                                v.tv_tool_state_icon.text = "🚫"
                                v.tv_tool_state.text = "已拒绝"
                                v.tv_tool_state.setTextColor(context.getColorCompat(R.color.ai_error_text))
                            }
                            else -> {
                                v.tv_tool_state_icon.text = "❌"
                                v.tv_tool_state.text = "出错"
                                v.tv_tool_state.setTextColor(context.getColorCompat(R.color.ai_error_text))
                            }
                        }
                    }

                    override fun registerListener(holder: ItemViewHolder) {}
                })
            addItemViewDelegate(
                VT_ERROR,
                object : ItemViewDelegate<ChatRow>(context, R.layout.ai_item_error) {
                    override fun convert(holder: ItemViewHolder, item: ChatRow, payloads: MutableList<Any>) {
                        holder.itemView.tv_error.text = (item as ChatRow.ErrorRow).message
                    }

                    override fun registerListener(holder: ItemViewHolder) {}
                })
            addItemViewDelegate(
                VT_CONFIRM,
                object : ItemViewDelegate<ChatRow>(context, R.layout.ai_item_confirm) {
                    override fun convert(holder: ItemViewHolder, item: ChatRow, payloads: MutableList<Any>) {
                        val c = item as ChatRow.Confirm
                        val v = holder.itemView
                        v.tv_proposal.text = c.proposalText
                        if (c.decided == null) {
                            v.confirm_actions.visibility = View.VISIBLE
                            v.tv_decided.visibility = View.GONE
                        } else {
                            v.confirm_actions.visibility = View.GONE
                            v.tv_decided.visibility = View.VISIBLE
                            if (c.decided == true) {
                                v.tv_decided.text = "✔ 已同意执行"
                                v.tv_decided.setTextColor(context.getColorCompat(R.color.ai_ok_text))
                            } else {
                                v.tv_decided.text = "✖ 已拒绝"
                                v.tv_decided.setTextColor(context.getColorCompat(R.color.ai_error_text))
                            }
                        }
                    }

                    override fun registerListener(holder: ItemViewHolder) {
                        holder.itemView.btn_approve.onClick {
                            getItem(holder.layoutPosition)?.let { row ->
                                if (row is ChatRow.Confirm && row.decided == null) {
                                    vm.approve(row.token, true)
                                    toast("已同意，正在执行写操作")
                                }
                            }
                        }
                        holder.itemView.btn_deny.onClick {
                            getItem(holder.layoutPosition)?.let { row ->
                                if (row is ChatRow.Confirm && row.decided == null) {
                                    vm.approve(row.token, false)
                                }
                            }
                        }
                    }
                })
        }

        override fun getItemViewType(item: ChatRow, position: Int): Int = when (item) {
            is ChatRow.Msg -> if (item.role == "user") VT_USER else VT_AI
            is ChatRow.ToolCard -> VT_TOOL
            is ChatRow.ErrorRow -> VT_ERROR
            is ChatRow.Confirm -> VT_CONFIRM
        }

        private fun formatElapsed(ms: Long): String =
            if (ms <= 0) "完成" else "完成 · ${ms / 1000.0}s"
    }

    private fun Context.getColorCompat(res: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, res)
}
