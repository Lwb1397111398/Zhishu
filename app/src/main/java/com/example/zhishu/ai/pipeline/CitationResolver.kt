package com.example.zhishu.ai.pipeline

import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.CitationStatus

/**
 * 把模型给出的引用标识落成引用关系（第四阶段 4A）。
 *
 * 这一步是「不允许模型凭空生成不存在的来源 ID」这条要求的落点。三种处理方式里
 * 只有一种是诚实的：
 *
 * - **静默丢掉**（4A 之前的做法）：`sourceRefs.filter { it in validChunkIds }`。
 *   引用消失了，问题也就消失了——那句需要出处的话变成了没人管的断言，
 *   阅读器还会显示「本段无引用」，看起来跟模型本来就没打算给出处一样。
 * - **就近匹配**：找一个最像的片段填上。这是拿一个我们自己编的出处给读者壮胆，
 *   比模型编的更糟——它带着「系统确认过」的外观。
 * - **记成未确认**：留着模型原样给的标识，标明没对上。用户看到的是
 *   「这一段模型说有出处，但我们没能对上」——这句话是真的。
 *
 * 所以这里两样都产出：能解析的成为 [CitationStatus.RESOLVED]，
 * 对不上的成为 [CitationStatus.UNCONFIRMED]，一条都不丢。
 */
internal object CitationResolver {

    /** 一个正文块的引用解析结果 */
    class BlockRefs(
        val blockId: String,
        val rawRefs: List<String>,
    )

    class Resolution(
        val citations: List<Citation>,
        /** blockId → 真正解析到的片段 ID。写进 `ChapterBlock.sourceRefs` 的就是这些 */
        val resolvedByBlock: Map<String, List<String>>,
    ) {
        val resolvedCount: Int get() = citations.count { it.status.isResolved }
        val unconfirmedCount: Int get() = citations.count { !it.status.isResolved }

        /** 未确认引用所在的块。阅读器要能指出「哪些正文块存在未确认引用」（4C） */
        val unconfirmedBlockIds: Set<String>
            get() = citations.filterNot { it.status.isResolved }.map { it.blockId }.toSet()
    }

    fun resolve(
        projectId: String,
        chapterId: String,
        versionId: String,
        blocks: List<BlockRefs>,
        validChunkIds: Set<String>,
    ): Resolution {
        val citations = mutableListOf<Citation>()
        val resolvedByBlock = mutableMapOf<String, List<String>>()

        blocks.forEach { block ->
            val resolved = mutableListOf<String>()
            // 同一个块里模型经常把同一个片段写两遍，去重后再编号，
            // 否则 order 会跳号、id 也会出现两条指向同一片段的重复引用
            val seen = mutableSetOf<String>()
            var order = 0
            block.rawRefs.forEach { raw ->
                val ref = raw.trim()
                if (ref.isEmpty() || !seen.add(ref)) return@forEach
                val known = ref in validChunkIds
                if (known) resolved += ref
                citations += Citation(
                    id = Citation.idFor(block.blockId, order),
                    projectId = projectId,
                    chapterId = chapterId,
                    versionId = versionId,
                    blockId = block.blockId,
                    chunkId = if (known) ref else null,
                    rawRef = ref,
                    status = if (known) CitationStatus.RESOLVED else CitationStatus.UNCONFIRMED,
                    order = order,
                )
                order += 1
            }
            resolvedByBlock[block.blockId] = resolved
        }
        return Resolution(citations = citations, resolvedByBlock = resolvedByBlock)
    }
}
