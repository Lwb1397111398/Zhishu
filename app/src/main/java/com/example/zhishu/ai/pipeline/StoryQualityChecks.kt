package com.example.zhishu.ai.pipeline

import com.example.zhishu.ai.outputs.VerificationIssueDto
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock

/**
 * 程序化文风预检（2026-09 文风修复）。
 *
 * 背景：核验（verify/verify_model）只查知识与推演，「写得不像小说」从来没有人查，
 * 文风劣化因此没有任何反馈回路——模型把正文写成无名「他」/「你」的推理独白、
 * 逐字复读提示词例句，都照样发布。本检查在正文生成后立即免费跑，
 * 只抓**机器能可靠判定的硬信号**，与模型核验的 `style` 类问题互补：
 *
 * 1. [checkTemplateEcho]：提示词例句被逐字复读 ≥2 次；
 * 2. [checkSentenceEcho]：同一句长句在章内原样出现 ≥2 次（口头禅/复读腔）；
 * 3. [checkNoDialogue]：全章没有任何对话；
 * 4. [checkStoryRatio]：STORY 块篇幅占比不足一半（说理淹没了故事）。
 *
 * 阈值刻意保守：宁漏勿误——误报会让用户对问题列表失去信任，
 * 也会把自动修复的调用预算烧在不存在的毛病上。
 * 检测不到具名人物这类需要理解文本的判断，留给核验模型去做。
 */
internal object StoryQualityChecks {

    /** 提示词里给过、历史上被模型整章复读的例句。新例句出现复读再往这里加 */
    private val TEMPLATE_PHRASES = listOf(
        "这里要修正一个容易混的地方",
    )

    /** 句子复读判定的最短长度：更短的重复多半是正常修辞 */
    private const val ECHO_MIN_SENTENCE_LENGTH = 12

    /** 短章不做整体判定（对话/占比这类全章级信号在小样本上不可靠） */
    private const val MIN_CHAPTER_LENGTH = 800

    /** STORY 块占比低于这个值才报——提示词要求的是「绝大部分」，0.5 是宽松下限 */
    private const val STORY_RATIO_FLOOR = 0.5

    fun check(blocks: List<ChapterBlock>): List<VerificationIssueDto> {
        if (blocks.isEmpty()) return emptyList()
        val totalLength = blocks.sumOf { it.text.length }
        if (totalLength < MIN_CHAPTER_LENGTH) return emptyList()
        return buildList {
            addAll(checkTemplateEcho(blocks))
            addAll(checkSentenceEcho(blocks))
            addAll(checkNoDialogue(blocks))
            addAll(checkStoryRatio(blocks, totalLength))
        }
    }

    /** 提示词例句被逐字复读：每次生成都同样发生的确定性病灶，必须机器抓 */
    private fun checkTemplateEcho(blocks: List<ChapterBlock>): List<VerificationIssueDto> =
        TEMPLATE_PHRASES.flatMap { phrase ->
            val hits = blocks.withIndex().filter { it.value.text.contains(phrase) }
            if (hits.size < 2) {
                emptyList()
            } else {
                listOf(
                    VerificationIssueDto(
                        type = "style",
                        blockId = hits[1].value.id,
                        message = "「$phrase」这句提示词例句在全章被原样复用了 ${hits.size} 次，" +
                            "读起来是复读腔。改写成这一处自己的说法，其余出现处保持各自的自然表达。",
                        suggestedFix = "把这一段的这句过渡换成贴合当下情节的说法，不要再套同一句模板。",
                    ),
                )
            }
        }

    /** 同一句长句原样出现 ≥2 次：不管来源，都是复读 */
    private fun checkSentenceEcho(blocks: List<ChapterBlock>): List<VerificationIssueDto> {
        val seen = HashMap<String, Int>()
        val issues = mutableListOf<VerificationIssueDto>()
        blocks.forEach { block ->
            sentences(block.text).forEach { sentence ->
                val count = seen.merge(sentence, 1, Int::plus) ?: 1
                if (count == 2) {
                    val excerpt = sentence.take(24)
                    issues += VerificationIssueDto(
                        type = "style",
                        blockId = block.id,
                        message = "同一句话在全章出现了两次（「$excerpt……」），是明显的复读。" +
                            "第二处要么删掉，要么换个说法。",
                        suggestedFix = "删掉或改写这一段里重复的那句话。",
                    )
                }
            }
        }
        return issues
    }

    /** 全章零对话：小说技法里「至少一处对话或动作场面」的机器可判部分 */
    private fun checkNoDialogue(blocks: List<ChapterBlock>): List<VerificationIssueDto> {
        val hasDialogue = blocks.any { block ->
            block.text.contains('「') || block.text.contains('”') ||
                block.text.contains('“') || block.text.contains('」')
        }
        return if (hasDialogue) {
            emptyList()
        } else {
            listOf(
                VerificationIssueDto(
                    type = "style",
                    blockId = null,
                    message = "全章没有一句对话，通篇是转述和议论——这读起来像讲义，不像小说。" +
                        "找一个关键节点让人物开口。",
                    suggestedFix = "挑一处人物做判断或撞上麻烦的地方，写出他实际说的话。",
                ),
            )
        }
    }

    /** STORY 占比过低：说理段淹没了叙事主体 */
    private fun checkStoryRatio(
        blocks: List<ChapterBlock>,
        totalLength: Int,
    ): List<VerificationIssueDto> {
        val storyLength = blocks.filter { it.blockType == BlockType.STORY }.sumOf { it.text.length }
        return if (totalLength > 0 && storyLength.toDouble() / totalLength >= STORY_RATIO_FLOOR) {
            emptyList()
        } else {
            listOf(
                VerificationIssueDto(
                    type = "style",
                    blockId = null,
                    message = "故事性段落（STORY）只占全章约 " +
                        "${(storyLength * 100 / totalLength).coerceAtMost(99)}%，" +
                        "大部分篇幅在说理。小说的推演应该藏在人物遭遇里，而不是旁白里。",
                    suggestedFix = "把说理内容改写成人物在具体处境里的遭遇与选择。",
                ),
            )
        }
    }

    private fun sentences(text: String): List<String> =
        text.split('。', '！', '？', '\n')
            .map { it.trim().replace(Regex("\\s+"), "") }
            .filter { it.length >= ECHO_MIN_SENTENCE_LENGTH }
}
