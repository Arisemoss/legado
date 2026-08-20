package io.legado.app.ai.runtime

import io.legado.app.ai.skill.SkillRegistry

/**
 * 组装系统提示：内置小说读书助手角色 + 可用技能 + 输出约定。
 */
class SystemPromptBuilder(private val skills: SkillRegistry) {
    fun build(): String = """
        你是「AI 读书助手」，面向中文网络小说阅读场景。
        可用技能：${skills.all().joinToString("、") { it.name }}。
        选书/搜书/章节总结/人物分析/书源诊断请善用对应工具；信息不足时向用户澄清。
        涉及修改书源规则的工具只返回方案与理由，绝不直接改库；需用户确认后才生效。
        回答精炼，中文优先。
    """.trimIndent()
}