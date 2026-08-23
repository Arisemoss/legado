package io.legado.app.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.ai.log.AiLog
import io.legado.app.base.BaseActivity
import kotlinx.android.synthetic.main.activity_ai_log.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.anko.sdk27.listeners.onClick
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 运行日志页：实时展示当次会话日志（模型请求/流式/工具/错误），
 * 支持复制、清空、分享完整日志文件（含历史会话），便于远程排障。
 */
class AiLogActivity : BaseActivity(R.layout.activity_ai_log) {

    companion object {
        /** 渲染上限，避免 TextView 过大卡顿；完整内容走「分享」 */
        private const val MAX_RENDER_LINES = 500

        private val COLOR_DEBUG = Color.parseColor("#8A94A6")
        private val COLOR_INFO = Color.parseColor("#1F2430")
        private val COLOR_WARN = Color.parseColor("#E08E00")
        private val COLOR_ERROR = Color.parseColor("#C0392B")
    }

    private val jobs = ArrayList<Job>()
    private var lastText: String? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        btn_back.onClick { finish() }
        btn_clear.onClick {
            AlertDialog.Builder(this)
                .setMessage("清空全部 AI 日志？（内存与文件都会清除）")
                .setPositiveButton("清空") { _, _ ->
                    AiLog.clear()
                    lastText = null
                    tv_log.text = ""
                    toast("已清空")
                }
                .setNegativeButton("取消", null)
                .show()
        }
        btn_copy.onClick {
            val text = tv_log.text?.toString().orEmpty()
            if (text.isBlank()) return@onClick
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ai_log", text))
            toast("已复制 ${text.lines().size} 行")
        }
        btn_share.onClick { shareFullFile() }

        // 实时刷新（1s 轮询内存缓冲；内容未变化时不重设文本，保持可选中/滚动位置）
        jobs += launch {
            while (isActive) {
                render()
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    override fun onDestroy() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        super.onDestroy()
    }

    private fun render() {
        val entries = AiLog.snapshot()
        if (entries.isEmpty()) {
            if (lastText != "") {
                lastText = ""
                tv_log.text = "暂无日志"
            }
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        val shown = entries.takeLast(MAX_RENDER_LINES)
        val sb = SpannableStringBuilder()
        if (entries.size > shown.size) {
            sb.append("…（仅显示最近 ${shown.size} 条，完整日志请点右上角分享）\n\n")
        }
        for (e in shown) {
            val color = when (e.level) {
                AiLog.L_D -> COLOR_DEBUG
                AiLog.L_W -> COLOR_WARN
                AiLog.L_E -> COLOR_ERROR
                else -> COLOR_INFO
            }
            val line = "${fmt.format(Date(e.time))} ${e.level}/${e.tag}: ${e.message}\n"
            val start = sb.length
            sb.append(line)
            sb.setSpan(
                ForegroundColorSpan(color), start, sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val text = sb.toString()
        if (text == lastText) return
        // 用户是否停在底部附近：是则跟随滚动，否则保持阅读位置
        val atBottom = sv_log.scrollY + sv_log.height >= tv_log.height - 80
        lastText = text
        tv_log.text = sb
        if (atBottom) sv_log.post { sv_log.fullScroll(View.FOCUS_DOWN) }
    }

    /** 分享完整日志文件内容（截取尾部，规避 Binder 1MB 限制） */
    private fun shareFullFile() {
        var text = AiLog.fileText()
        if (text.isBlank()) {
            toast("日志文件为空")
            return
        }
        val lines = text.lines()
        if (lines.size > 1200) {
            text = "…（前段省略，共${lines.size}行）\n" + lines.takeLast(1200).joinToString("\n")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "legado AI 运行日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "分享日志"))
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
