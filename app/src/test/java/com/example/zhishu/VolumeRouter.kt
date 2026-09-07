package com.example.zhishu

import com.example.zhishu.ai.JsonDecoder
import com.example.zhishu.ai.ModelCallStats
import com.example.zhishu.ai.ModelError
import com.example.zhishu.ai.ModelRequest
import com.example.zhishu.ai.ModelRouter
import com.example.zhishu.ai.PromptProvider
import com.example.zhishu.domain.model.ModelRole

/**
 * 分卷测试用的模型桩（第三阶段 3C）。
 *
 * 与 [ScriptedRouter] 的区别：这个能控制「首次规划给几章」「续卷讲哪些知识点」
 * 「续卷是否失败」，并且把续卷提示词原样留下来——
 * 「续卷上下文里不能出现历史正文」这条只能从提示词里验。
 */
internal class VolumeRouter : ModelRouter {

    /** 首次规划返回几章。用来验证「模型多给了也只收前 8 章」 */
    var bibleChapterCount: Int = 5

    /** 续卷章节要讲的知识点标题。默认从提示词的「待讲」清单取（见 [nextVolumeJson]） */
    var nextVolumeNodeTitles: List<String> = DEFAULT_NEXT_TITLES

    var failNextVolume: Boolean = false

    var lastNextVolumePrompt: String = ""

    override suspend fun <T> generate(
        role: ModelRole,
        request: ModelRequest,
        decoder: JsonDecoder<T>,
        onStats: (ModelCallStats) -> Unit,
        onAttempt: (com.example.zhishu.ai.CallAttempt) -> Unit,
    ): Result<T> {
        val text = when {
            request.user.contains("[[MAP_SKELETON]]") -> SKELETON_JSON
            request.user.contains("[[MAP_CHAIN]]") -> chainNodesJson(request.user)
            request.user.contains("[[MAP_AUDIT]]") -> AUDIT_JSON
            request.user.contains("[[MAP]]") -> SKELETON_JSON
            request.user.contains("[[OUTLINE]]") -> RouteStubs.outlineJson(request.user)
            request.user.contains("[[CONTRACT]]") -> RouteStubs.contractJson(request.user)
            request.user.contains("[[BIBLE]]") -> bibleJson()
            request.user.contains("[[VOLUME]]") -> {
                lastNextVolumePrompt = request.user
                if (failNextVolume) return Result.failure(ModelError.Server("500"))
                nextVolumeJson(request.user)
            }
            request.user.contains("[[CHAPTER]]") -> chapterJson(request.user)
            request.user.contains("[[VERIFY]]") -> """{"issues":[]}"""
            request.user.contains("[[QUIZ]]") -> quizJson(request.user)
            else -> error("没有匹配的测试数据: ${request.user.take(40)}")
        }
        return try {
            Result.success(decoder.decode(text))
        } catch (e: Exception) {
            Result.failure(ModelError.Decode(e.message ?: "decode failed"))
        }
    }

    /** 第一卷只讲「继承」「遗产范围」，留两个知识点给续卷 */
    private fun bibleJson(): String {
        val chapters = (1..bibleChapterCount).joinToString(",") { order ->
            val node = if (order == 1) "继承" else "遗产范围"
            """{"order":$order,"title":"第${order}章","plotGoal":"讲$node","learningGoals":["$node"],""" +
                """"nodeTitles":["$node"],"wordTarget":1200,"openingHook":"第${order}章的开场",""" +
                """"closingHook":"第${order}章的口子","chapterType":"INTRODUCE",""" +
                """"contract":{"entryQuestion":"开场问题","priorIntuition":"原有直觉","obstacle":"遇到的阻碍","knowledgeGap":"需要一个新解释","introducedNodeTitles":["$node"],"appliedNodeTitles":[],"requiredBeats":["开场","引入$node","收束"],"resultingChange":"对${node}有了判断","bridgeToNext":"下一个问题"}}"""
        }
        return """{"world":"滨海市","characters":[{"name":"林默","role":"主角","description":"律师"}],""" +
            """"conflict":"遗产纠纷","style":"悬疑","chapters":[$chapters]}"""
    }

    /**
     * 续卷产出。
     *
     * 默认讲 [nextVolumeNodeTitles]（显式指定的一组）；没显式指定时，从提示词的
     * 「待讲」清单里取——三段式之后第一卷会吃掉全部种子节点，续卷讲什么必须看
     * 实际还剩什么，写死两个标题会让「待讲节点被清空」这类用例永远对不上。
     */
    private fun nextVolumeJson(prompt: String = ""): String {
        // 「待讲」走的是 formatNodesWithDerivation，格式是逐行的「- 标题：定义」，
        // 不是逗号分隔的清单——按行取标题，否则解析出来永远是空
        // 「待讲=」后面第一段就是第一行（没有换行前缀），所以整段都要扫，
        // 不能 drop(1)——那会吃掉第一个待讲节点
        val pending = prompt.substringAfter("待讲=", "")
            .lineSequence()
            .mapNotNull { NODE_IN_PROMPT.find(it)?.groupValues?.get(1)?.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val titles = if (nextVolumeNodeTitles !== DEFAULT_NEXT_TITLES) {
            nextVolumeNodeTitles
        } else {
            pending.ifEmpty { nextVolumeNodeTitles }
        }
        val chapters = titles.mapIndexed { index, title ->
            """{"order":${index + 1},"title":"新卷第${index + 1}章","plotGoal":"讲$title",""" +
                """"learningGoals":["$title"],"nodeTitles":["$title"],"wordTarget":1200,""" +
                """"openingHook":"新卷第${index + 1}章的开场","closingHook":"新卷第${index + 1}章的口子","chapterType":"INTRODUCE",""" +
                """"contract":{"entryQuestion":"开场问题","priorIntuition":"原有直觉","obstacle":"遇到的阻碍","knowledgeGap":"需要一个新解释","introducedNodeTitles":["$title"],"appliedNodeTitles":[],"requiredBeats":["开场","引入$title","收束"],"resultingChange":"对${title}有了判断","bridgeToNext":"下一个问题"}}"""
        }.joinToString(",")
        return """{"volumeTitle":"第二卷","volumeGoal":"讲清遗嘱与法定继承的关系","chapters":[$chapters]}"""
    }

    /**
     * 正文按「本章实际关联的知识点」生成。
     *
     * 不能所有章都写死同一个知识点：核验器要求正文标注的知识点属于本章，
     * 写死了第二章就通不过——那是校验在正常工作，不是测试数据的自由。
     */
    private fun chapterJson(prompt: String): String {
        val node = NODE_IN_PROMPT.find(prompt)?.groupValues?.get(1)?.trim() ?: "继承"
        return """{"title":"某一章","summary":"讲了$node","endingState":"房子归属仍未决",""" +
            """"runningStateUpdate":"案子推进了一步","coveredTitles":["$node"],"openThreads":[],""" +
            """"blocks":[{"type":"STORY","text":"老人立下遗嘱，子女各怀心思。","knowledgeNodeTitles":["$node"],"sourceRefs":["c-1"]},""" +
            """{"type":"EXPLANATION","text":"$node 是这一章要讲透的东西。","knowledgeNodeTitles":["$node"],"sourceRefs":["c-1"]}]}"""
    }

    private fun quizJson(prompt: String): String {
        val node = NODE_IN_PROMPT.find(prompt)?.groupValues?.get(1)?.trim() ?: "继承"
        return """{"questions":[""" +
            """{"question":"$node 是什么？","type":"TRUE_FALSE","options":["对","错"],"answer":"对","explanation":"e","nodeTitles":["$node"]},""" +
            """{"question":"$node 与遗产有关吗？","type":"TRUE_FALSE","options":["是","否"],"answer":"是","explanation":"e","nodeTitles":["$node"]},""" +
            """{"question":"回忆 $node 的定义。","type":"RECALL","options":[],"answer":"定义","explanation":"e","nodeTitles":["$node"]}""" +
            """]}"""
    }

    private companion object {
        /** 从章节提示词里取出本章第一个知识点标题（formatNodesWithDerivation 的「- 标题：定义」格式） */
        val NODE_IN_PROMPT = Regex("- ([^：\\n]+)：")

        /** 续卷默认标题。用 identity 比较来区分「用户显式指定」与「跟随待讲清单」 */
        val DEFAULT_NEXT_TITLES = listOf("遗嘱继承", "法定继承")

        const val MAP_JSON =
            """{"nodes":[""" +
                """{"title":"继承","definition":"财产权利义务的转移制度。","depth":1,"sourceRefs":["c-1"]},""" +
                """{"title":"遗产范围","definition":"死亡时遗留的个人合法财产。","depth":2,"sourceRefs":["c-1"]},""" +
                """{"title":"遗嘱继承","definition":"有有效遗嘱时按遗嘱继承。","depth":2,"sourceRefs":["c-1"]},""" +
                """{"title":"法定继承","definition":"无遗嘱时按法定顺序继承。","depth":2,"sourceRefs":["c-1"]}""" +
                """],"gaps":[],"conflicts":[]}"""

        /** 批次化骨架：一条链、四个节点，与原 MAP_JSON 的节点集一致 */
        const val SKELETON_JSON =
            """{"chains":[{"theme":"继承制度演进","opening":"从财产传承开始","nodeTitles":["继承","遗产范围","遗嘱继承","法定继承"]}],"gaps":[],"conflicts":[]}"""
        const val AUDIT_JSON = """{"gaps":[],"conflicts":[]}"""
    }

    /** 从单链 prompt 提取 (nodeKey, title) 对并回传带 nodeKey 的节点（与原 MAP_JSON 的节点集一致） */
    private fun chainNodesJson(prompt: String): String {
        val entries = Regex("""(nk-[^\s：]+)：([^\n]+)""")
            .findAll(prompt)
            .map { it.groupValues[1] to it.groupValues[2].trim() }
            .toList()
        val nodeJson = entries.map { (key, title) ->
            """{"nodeKey":"$key","title":"$title","definition":"$title 的定义。","depth":1,"sourceRefs":["c-1"]}"""
        }.joinToString(",")
        return """{"nodes":[$nodeJson]}"""
    }
}

/** 分卷测试的阶段标记 prompt，额外区分续卷阶段 */
internal class VolumePromptProvider : PromptProvider {
    private val map = mapOf(
        "knowledge_map_skeleton" to "[[MAP_SKELETON]]",
        "knowledge_map_chain" to "[[MAP_CHAIN]] 节点标识清单：{nodeKeyTitles}",
        "knowledge_map_audit" to "[[MAP_AUDIT]]",
        "knowledge_map" to "[[MAP]]",
        "knowledge_map_model" to "[[MAP]]",
        "story_bible" to "[[BIBLE]]",
        "route_outline" to RouteStubs.OUTLINE_TEMPLATE,
        "chapter_contract" to RouteStubs.CONTRACT_TEMPLATE,
        // 续卷提示词保留几个关键字段，用来断言喂进去的到底是什么
        "next_volume" to "[[VOLUME]] 累积状态={runningState} 上一章结束时的处境={lastEndingState} " +
            "已讲={coveredTitles} 待讲={pendingNodes}",
        "chapter" to "[[CHAPTER]] {nodes}",
        "verify" to "[[VERIFY]]",
        "verify_model" to "[[VERIFY]]",
        "quiz" to "[[QUIZ]] {nodes}",
    )

    override fun load(name: String): String = map[name] ?: name
}
