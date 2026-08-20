package com.example.zhishu

import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
import com.example.zhishu.domain.model.ModelRole
import kotlinx.coroutines.CancellationException

/**
 * 取消/解耦测试用的可控模型桩。
 *
 * 与 GenerationTest 里那个 FakeRouter 的区别：这个能被指定「在哪个阶段取消」
 * 或「在哪个阶段失败」，用来验证收尾逻辑，而不是验证正常产出。
 */
internal class ScriptedRouter : ModelRouter {

    /** 在这个阶段抛协程取消 */
    var cancelAt: Phase? = null

    /** 在这个阶段返回失败 */
    var failAt: Phase? = null
    var failWith: ModelError = ModelError.Server("500")

    /** 记录实际被调用过的阶段，用来断言「测验重试没有再生成正文」 */
    val calls = mutableListOf<Phase>()

    /**
     * 每个阶段最后一次收到的用户提示词（第三阶段 3B）。
     *
     * 用来断言「喂给模型的前情到底是哪一段」——上一章结尾状态、本章开头钩子、
     * 下一章的口子，这几样从哪来是 3B 的全部意义，只能从提示词里验。
     */
    val lastPrompts = mutableMapOf<Phase, String>()

    /**
     * 每个阶段开始前的观察点（第三阶段 3A）。
     *
     * 用来断言「重生成期间章节不会短暂变得不可读」——这种时序性质只能在生成还没结束时看，
     * 生成完再查库是看不出来的。
     */
    var onPhase: (suspend (Phase) -> Unit)? = null

    enum class Phase { MAP, BIBLE, CHAPTER, VERIFY, QUIZ, REWRITE }

    var chapterJson: String =
        """{"title":"第一章","summary":"老人立下遗嘱","endingState":"遗嘱真伪待查",""" +
            """"runningStateUpdate":"林默接手案子","coveredTitles":["意思表示"],"openThreads":[],""" +
            """"blocks":[{"type":"STORY","text":"老人立下遗嘱，子女各怀心思。","knowledgeNodeTitles":["意思表示"],"sourceRefs":["c-1"]},""" +
            """{"type":"EXPLANATION","text":"意思表示是民事法律行为的核心要素。","knowledgeNodeTitles":["意思表示"],"sourceRefs":["c-1"]}]}"""
    var verifyJson: String = """{"issues":[]}"""

    /**
     * 按顺序返回的核验结果（4B）。
     *
     * 局部重写要走两次核验：第一次指出问题，重写之后再核一次。
     * 一个 [verifyJson] 表达不了「这次有问题、下次没有」，而那正是
     * 整条局部重写路径唯一值得测的东西。队列空了就退回 [verifyJson]。
     */
    val verifyQueue: MutableList<String> = mutableListOf()

    /** 局部重写的产出（4B）。队列空了就退回 [rewriteJson] */
    var rewriteJson: String =
        """{"text":"改写后的这一段。","knowledgeNodeTitles":["意思表示"],""" +
            """"sourceRefs":["c-1"],"changeNote":"按资料改回正确的顺序"}"""
    val rewriteQueue: MutableList<String> = mutableListOf()

    var quizJson: String = """
        {"questions":[
          {"question":"意思表示是民法概念吗？","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["意思表示"]},
          {"question":"它是民事法律行为的核心要素吗？","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["意思表示"]},
          {"question":"请回忆意思表示的定义。","type":"RECALL","options":[],"answer":"把内心意思表达于外","explanation":"e","nodeTitles":["意思表示"]}
        ]}
    """.trimIndent()

    override suspend fun <T> generate(
        role: ModelRole,
        request: ModelRequest,
        decoder: JsonDecoder<T>,
    ): Result<T> {
        val phase = when {
            request.user.contains("[[MAP]]") -> Phase.MAP
            request.user.contains("[[BIBLE]]") -> Phase.BIBLE
            request.user.contains("[[CHAPTER]]") -> Phase.CHAPTER
            request.user.contains("[[VERIFY]]") -> Phase.VERIFY
            request.user.contains("[[QUIZ]]") -> Phase.QUIZ
            request.user.contains("[[REWRITE]]") -> Phase.REWRITE
            else -> error("没有匹配的测试数据: ${request.user.take(40)}")
        }
        calls += phase
        lastPrompts[phase] = request.user
        onPhase?.invoke(phase)
        if (phase == cancelAt) throw CancellationException("用户取消")
        if (phase == failAt) return Result.failure(failWith)

        val text = when (phase) {
            Phase.MAP -> MAP_JSON
            Phase.BIBLE -> BIBLE_JSON
            Phase.CHAPTER -> chapterJson
            Phase.VERIFY -> verifyQueue.removeFirstOrNull() ?: verifyJson
            Phase.QUIZ -> quizJson
            Phase.REWRITE -> rewriteQueue.removeFirstOrNull() ?: rewriteJson
        }
        return try {
            Result.success(decoder.decode(text))
        } catch (e: Exception) {
            Result.failure(ModelError.Decode(e.message ?: "decode failed"))
        }
    }

    private companion object {
        const val MAP_JSON =
            """{"nodes":[{"title":"意思表示","definition":"民事法律行为的核心要素。","depth":1,"sourceRefs":["c-1"]}],"gaps":[],"conflicts":[]}"""
        const val BIBLE_JSON =
            """{"world":"滨海市","characters":[{"name":"林默","role":"主角","description":"律师"}],""" +
                """"conflict":"遗产纠纷","style":"悬疑","chapters":[""" +
                """{"order":1,"title":"第一章","plotGoal":"引出遗嘱","learningGoals":["意思表示"],""" +
                """"nodeTitles":["意思表示"],"wordTarget":1000,"openingHook":"从一封律师函开始"},""" +
                """{"order":2,"title":"第二章","plotGoal":"庭审","learningGoals":["意思表示"],""" +
                """"nodeTitles":["意思表示"],"wordTarget":1000,"openingHook":"庭审当天的清晨"}]}"""
    }
}

/** 阶段标记 prompt，与 GenerationTest 同一套约定 */
internal class TaggedPromptProvider(
    /**
     * 章节模板。默认只有阶段标记；需要断言「喂进去的前情是哪一段」时
     * 传一个带占位符的模板进来（第三阶段 3B）。
     */
    private val chapterTemplate: String = "[[CHAPTER]]",
    /**
     * 局部重写模板（4B）。默认就带上下段与问题清单的占位符——
     * 「改出来的段落接不接得上原位」只能从喂进去的提示词里验。
     */
    private val rewriteTemplate: String =
        "[[REWRITE]] 上一段=<{previousBlock}> 本段=<{targetBlock}> 下一段=<{nextBlock}> " +
            "问题=<{issues}> 资料区=<{sourceSection}>",
    /**
     * 核验模板。默认带上 `{blocks}`——「核验看到的是哪几块正文」
     * 是局部重写唯一能从外部验证的点（4B）：只改一块，但核验必须看到全章。
     */
    private val verifyTemplate: String = "[[VERIFY]] 正文=<{blocks}>",
) : PromptProvider {
    private val map = mapOf(
        "knowledge_map" to "[[MAP]]",
        "knowledge_map_model" to "[[MAP]]",
        "story_bible" to "[[BIBLE]]",
        "chapter" to chapterTemplate,
        "verify" to verifyTemplate,
        "verify_model" to verifyTemplate,
        "quiz" to "[[QUIZ]]",
        "rewrite_block" to rewriteTemplate,
    )

    override fun load(name: String): String = map[name] ?: name
}
