package com.example.zhishu.data.transfer

import android.content.Context
import android.net.Uri
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.CitationStatus
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.StoryBible
import com.example.zhishu.domain.model.VerificationIssue
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class TransferManager(private val container: AppContainer) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun export(context: Context, uri: Uri): Result<String> {
        return runCatching {
            val projects = container.projectRepository.observeAll().first()

            val sources = mutableListOf<SourceDocument>()
            val chunks = mutableListOf<SourceChunk>()
            val knowledge = mutableListOf<KnowledgeNode>()
            val plans = mutableListOf<ChapterPlan>()
            val versions = mutableListOf<ChapterVersion>()
            val blocks = mutableListOf<ChapterBlock>()
            val citations = mutableListOf<Citation>()
            val issues = mutableListOf<VerificationIssue>()
            val quizzes = mutableListOf<Quiz>()
            val reviews = mutableListOf<ReviewItem>()
            val progress = mutableListOf<ReadingProgress>()
            val answers = mutableListOf<AnswerRecord>()
            val bibles = mutableListOf<StoryBible>()

            projects.forEach { project ->
                sources += container.sourceRepository.getByProject(project.id)
                chunks += container.sourceRepository.getChunksByProject(project.id)
                knowledge += container.knowledgeRepository.getByProject(project.id)
                val projectPlans = container.chapterRepository.getPlans(project.id)
                plans += projectPlans
                // 版本行与全部历史正文都导出：只导 active 版本的话，
                // 恢复出来的库就再也没有「上一版正文」可查了（第三阶段 3A）
                versions += container.chapterRepository.getVersionsByProject(project.id)
                projectPlans.forEach { plan ->
                    blocks += container.chapterRepository.getAllVersionsBlocks(plan.id)
                }
                // 引用同样导出全部历史版本：正文都留着了，引用只留当前版本的话，
                // 恢复出来的旧版本会变成一段没有出处的正文（4A）
                citations += container.citationRepository.getAllVersionsByProject(project.id)
                // 核验问题同样导出全部历史版本：它们记录的是「这一版当时被指出了什么」，
                // 只导当前版本的话，恢复出来的历史版本会显得从未被核验过（4B）
                issues += container.issueRepository.getAllVersionsByProject(project.id)
                val projectQuizzes = container.quizRepository.getByProject(project.id)
                quizzes += projectQuizzes
                // 导出真实答题记录，而不是伪造 restored-* 记录
                answers += container.quizRepository.getAnswers(projectQuizzes.map { it.id })
                reviews += container.reviewRepository.getByProject(project.id)
                container.progressRepository.get(project.id)?.let { progress += it }
                // 故事总纲与滚动记忆，否则恢复后讲法与进度全丢（问题5）
                container.storyBibleRepository.get(project.id)?.let { bibles += it }
            }

            val entries = linkedMapOf<String, String>()
            entries["manifest.json"] = json.encodeToString(
                TransferManifest(
                    app = "zhishu",
                    formatVersion = FORMAT_VERSION,
                    exportedAt = System.currentTimeMillis(),
                    projects = projects.size,
                ),
            )
            entries["projects.json"] = json.encodeToString(projects)
            entries["sources.json"] = json.encodeToString(sources)
            entries["chunks.json"] = json.encodeToString(chunks)
            entries["knowledge.json"] = json.encodeToString(knowledge)
            entries["chapters.json"] = json.encodeToString(plans)
            entries["chapter_versions.json"] = json.encodeToString(versions)
            entries["blocks.json"] = json.encodeToString(blocks)
            entries["citations.json"] = json.encodeToString(citations)
            entries["verification_issues.json"] = json.encodeToString(issues)
            entries["quizzes.json"] = json.encodeToString(quizzes)
            entries["answers.json"] = json.encodeToString(answers)
            entries["reviews.json"] = json.encodeToString(reviews)
            entries["progress.json"] = json.encodeToString(progress)
            entries["story_bibles.json"] = json.encodeToString(bibles)

            val resolver = context.contentResolver
            resolver.openOutputStream(uri)?.use { output ->
                ZipOutputStream(output.buffered()).use { zip ->
                    entries.forEach { (name, content) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(content.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            } ?: throw IllegalStateException("无法写入所选位置")
            "已导出 ${projects.size} 个项目（不含 API Key）"
        }
    }

    suspend fun import(context: Context, uri: Uri): Result<String> {
        return runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            val files = input.use { readZipSafely(it) }
            val payload = parseAndValidate(files)
            writeAtomically(payload)
            "已恢复 ${payload.projects.size} 个项目"
        }
    }

    /**
     * 有界解压（N7）：限制条目数、单条大小、总解压大小，拒绝重复条目与路径穿越条目，
     * 避免 ZIP bomb 直接 readBytes() 打爆内存。
     */
    private fun readZipSafely(input: InputStream): Map<String, String> {
        val files = mutableMapOf<String, String>()
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount += 1
                if (entryCount > MAX_ENTRIES) {
                    throw IllegalStateException("备份文件条目过多（超过 $MAX_ENTRIES），已拒绝导入")
                }
                val name = entry.name
                if (!entry.isDirectory) {
                    if (name.contains("..") || name.startsWith("/") || name.contains('\\')) {
                        throw IllegalStateException("备份文件包含非法路径条目：$name")
                    }
                    if (files.containsKey(name)) {
                        throw IllegalStateException("备份文件包含重复条目：$name")
                    }
                    val bytes = readBounded(zip, MAX_ENTRY_BYTES, name)
                    totalBytes += bytes.size
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw IllegalStateException("备份内容超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB 上限，已拒绝导入")
                    }
                    files[name] = bytes.toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return files
    }

    private fun readBounded(input: InputStream, limit: Int, name: String): ByteArray {
        val buffer = ByteArray(READ_BUFFER)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) {
                throw IllegalStateException("备份条目 $name 超过 ${limit / 1024 / 1024} MB 上限，已拒绝导入")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** 先完整解析并校验引用关系，任何一处不合法都不写数据库（N7） */
    private fun parseAndValidate(files: Map<String, String>): ImportPayload {
        val manifest = files["manifest.json"]?.let {
            json.decodeFromString<TransferManifest>(it)
        } ?: throw IllegalStateException("不是有效的知述备份文件")
        if (manifest.app != "zhishu") throw IllegalStateException("不是知述备份文件")
        if (manifest.formatVersion > FORMAT_VERSION) throw IllegalStateException("备份版本过新，请升级应用")

        val projects = decodeList<Project>(files, "projects.json")
        if (projects.isEmpty()) throw IllegalStateException("备份中没有项目数据")
        val projectIds = projects.map { it.id }.toSet()

        val sources = decodeList<SourceDocument>(files, "sources.json")
            .filter { it.projectId in projectIds }
        val sourceIds = sources.map { it.id }.toSet()
        val sourceProject = sources.associate { it.id to it.projectId }

        val chunks = decodeList<SourceChunk>(files, "chunks.json")
            .filter { it.sourceId in sourceIds }
            // 旧备份的 chunk 没有 projectId，这里按所属资料补齐（B1 迁移兼容）
            .map { chunk ->
                if (chunk.projectId.isBlank()) {
                    chunk.copy(projectId = sourceProject[chunk.sourceId].orEmpty())
                } else {
                    chunk
                }
            }

        val knowledge = decodeList<KnowledgeNode>(files, "knowledge.json")
            .filter { it.projectId in projectIds }
        val plans = decodeList<ChapterPlan>(files, "chapters.json")
            .filter { it.projectId in projectIds }
        val planProject = plans.associate { it.id to it.projectId }
        val rawBlocks = decodeList<ChapterBlock>(files, "blocks.json")
            .filter { it.chapterId in planProject.keys }
            .map { block ->
                if (block.projectId.isBlank()) {
                    block.copy(projectId = planProject[block.chapterId].orEmpty())
                } else {
                    block
                }
            }
        // 旧备份（v3 及更早）里没有版本信息，这里按与 v5→v6 迁移完全相同的规则补齐（3A）
        val versioned = normalizeVersions(
            plans = plans,
            imported = decodeList<ChapterVersion>(files, "chapter_versions.json")
                .filter { it.chapterId in planProject.keys }
                .map { version ->
                    if (version.projectId.isBlank()) {
                        version.copy(projectId = planProject[version.chapterId].orEmpty())
                    } else {
                        version
                    }
                },
            blocks = rawBlocks,
            // 覆盖关系按标题补 ID（3D）：只在标题在本项目里唯一时才补，
            // 重名或对不上的留空——留空会被界面报成「待确认」，硬猜会把讲过的当成没讲过
            titleToNodeId = knowledge.groupBy { it.projectId to it.title.trim() }
                .filterValues { it.size == 1 }
                .mapValues { (_, group) -> group.first().id },
        )
        // 引用关系（4A）。v6 及更早的备份没有 citations.json，
        // 这时按正文块自己的 sourceRefs 重建，规则与 v9→v10 迁移逐字一致
        val citations = normalizeCitations(
            blocks = versioned.blocks,
            imported = decodeList<Citation>(files, "citations.json"),
            knownChunkIds = chunks.map { it.id }.toSet(),
        )
        val planIds = planProject.keys
        val quizzes = decodeList<Quiz>(files, "quizzes.json")
            .filter { it.projectId in projectIds && it.chapterId in planIds }
            // 未绑定版本或指向不存在版本的历史题，绑到该章当前版本上，
            // 否则它们会跟着以后任何一次重生成的新正文一起显示
            .map { quiz ->
                if (quiz.versionId.isBlank() || quiz.versionId !in versioned.versionIds) {
                    quiz.copy(versionId = versioned.activeVersionOf[quiz.chapterId].orEmpty())
                } else {
                    quiz
                }
            }
        val quizIds = quizzes.map { it.id }.toSet()
        // 一题一条记录，去重后再写，符合唯一索引约束（N12）
        val answers = decodeList<AnswerRecord>(files, "answers.json")
            .filter { it.quizId in quizIds && it.projectId in projectIds }
            .groupBy { it.quizId }
            .map { (_, records) -> records.maxBy { it.answeredAt } }
        val nodeIds = knowledge.map { it.id }.toSet()
        val reviews = decodeList<ReviewItem>(files, "reviews.json")
            .filter { it.projectId in projectIds && it.nodeId in nodeIds }
        val progress = decodeList<ReadingProgress>(files, "progress.json")
            .filter { it.projectId in projectIds && it.chapterId in planIds }
        // 旧备份没有这个文件，decodeList 返回空即可，向后兼容
        val bibles = decodeList<StoryBible>(files, "story_bibles.json")
            .filter { it.projectId in projectIds }

        // 核验问题（4B）。v7 及更早的备份没有这个文件，decodeList 返回空即可
        val issues = normalizeIssues(
            imported = decodeList<VerificationIssue>(files, "verification_issues.json"),
            blocks = citations.blocks,
            versionIds = versioned.versionIds,
        )

        return ImportPayload(
            projects = projects,
            sources = sources,
            chunks = chunks,
            knowledge = knowledge,
            plans = versioned.plans,
            versions = versioned.versions,
            // 块的 sourceRefs 只留真的解析到的片段，与 citations 保持一致：
            // 同一件事有两种表示时，它们不一致的那天就是 bug 出现的那天
            blocks = citations.blocks,
            citations = citations.citations,
            issues = issues,
            quizzes = quizzes,
            answers = answers,
            reviews = reviews,
            progress = progress,
            bibles = bibles,
        )
    }

    /**
     * 把导入进来的数据整理成「每章至少一个版本、每块都属于某个版本、
     * 章节指针只指向真的存在且已发布的版本」（第三阶段 3A）。
     *
     * 三种输入都要处理：
     * - v4 备份：自带版本行，原样保留，只校验指针合法；
     * - v1～v3 备份：完全没有版本概念，为每章补一个初始版本，规则与 v5→v6 迁移一致；
     * - 手工改坏的备份：blocks 指向不存在的版本、章节指向没发布的版本，一律纠正到当前版本。
     *
     * 纠正而不是丢弃：正文一个块都不删，只是重新挂对。
     */
    private fun normalizeVersions(
        plans: List<ChapterPlan>,
        imported: List<ChapterVersion>,
        blocks: List<ChapterBlock>,
        titleToNodeId: Map<Pair<String, String>, String>,
    ): VersionedChapters {
        val now = System.currentTimeMillis()
        val byChapter = imported.groupBy { it.chapterId }
        val blocksByVersion = blocks.groupBy { it.versionId }
        val versions = mutableListOf<ChapterVersion>()
        val activeVersionOf = mutableMapOf<String, String>()
        val fallbackVersionOf = mutableMapOf<String, String>()

        plans.forEach { plan ->
            val existing = byChapter[plan.id].orEmpty().sortedBy { it.versionNumber }
            val chapterVersions = existing.ifEmpty {
                listOf(initialVersionFor(plan, now))
            }
            // 兜底版本：优先已发布的那一个，否则最后一个。孤儿正文块挂到它上面
            val fallback = chapterVersions.lastOrNull { it.status == ChapterVersionStatus.PUBLISHED }
                ?: chapterVersions.last()
            fallbackVersionOf[plan.id] = fallback.id

            // 指针只认「存在 + 已发布」的版本；否则按章节可读性重新决定
            val declared = chapterVersions.firstOrNull {
                it.id == plan.activeVersionId && it.status == ChapterVersionStatus.PUBLISHED
            }
            val resolved = declared
                ?: chapterVersions.lastOrNull { it.status == ChapterVersionStatus.PUBLISHED }
                    ?.takeIf { plan.isReadable }
            if (resolved != null) activeVersionOf[plan.id] = resolved.id

            // 归档 vs 放弃（3D）：v4 备份里被取代的旧正文一律记成 ABANDONED。
            // 有正文块、且比当前指针那一版旧 → 它成功过，只是被取代 → ARCHIVED。
            // 判据与 v8→v9 迁移逐字一致，否则同一份数据「升级上来」和「导入进来」结果会不同。
            val activeNumber = resolved?.versionNumber ?: 0
            versions += chapterVersions.map { version ->
                val supersededBody = version.status == ChapterVersionStatus.ABANDONED &&
                    version.versionNumber < activeNumber &&
                    blocksByVersion[version.id].orEmpty().isNotEmpty()
                version.copy(
                    status = if (supersededBody) ChapterVersionStatus.ARCHIVED else version.status,
                    coveredNodeIds = version.coveredNodeIds.ifEmpty {
                        resolveNodeIds(version.projectId, version.coveredTitles, titleToNodeId)
                    },
                )
            }
        }

        val versionIds = versions.map { it.id }.toSet()
        return VersionedChapters(
            plans = plans.map { plan ->
                plan.copy(
                    activeVersionId = activeVersionOf[plan.id],
                    // v4 及更早的备份没有 openingHook：未发布章节的 carryOver 装的就是开头钩子，
                    // 按 v6→v7 的同一条规则补上（3B）。
                    // 这是全项目**唯一**两处允许读 carryOver 的地方（另一处见 initialVersionFor），
                    // 因为它们处理的就是那一列还有含义的旧数据（3D）
                    openingHook = plan.openingHook.ifBlank { legacyOpeningHook(plan) },
                    coveredNodeIds = plan.coveredNodeIds.ifEmpty {
                        resolveNodeIds(plan.projectId, plan.coveredTitles, titleToNodeId)
                    },
                )
            },
            versions = versions,
            blocks = blocks.map { block ->
                if (block.versionId.isBlank() || block.versionId !in versionIds) {
                    block.copy(versionId = fallbackVersionOf[block.chapterId].orEmpty())
                } else {
                    block
                }
            },
            versionIds = versionIds,
            activeVersionOf = activeVersionOf,
        )
    }

    /**
     * 旧备份的覆盖关系按标题补 ID（3D）。
     *
     * 只在标题于本项目内唯一时才补。对不上的不补也不丢：标题仍然留在 coveredTitles 里，
     * 界面上会把这类内容列成「待确认」，由用户判断——直接当成「没讲过」会让续卷
     * 把已经讲透的东西再讲一遍。
     */
    private fun resolveNodeIds(
        projectId: String,
        titles: List<String>,
        titleToNodeId: Map<Pair<String, String>, String>,
    ): List<String> = titles
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { titleToNodeId[projectId to it] }
        .distinct()

    /** 旧备份补出来的初始版本。endingState 只在章节可读时才取 carryOver（未发布时它装的是开头钩子） */
    private fun initialVersionFor(plan: ChapterPlan, now: Long): ChapterVersion = ChapterVersion(
        id = plan.id + AppDatabase.INITIAL_VERSION_SUFFIX,
        chapterId = plan.id,
        projectId = plan.projectId,
        versionNumber = 1,
        status = when {
            plan.isReadable -> ChapterVersionStatus.PUBLISHED
            plan.status == ChapterStatus.VERIFYING -> ChapterVersionStatus.VERIFYING
            else -> ChapterVersionStatus.DRAFT
        },
        title = plan.title,
        summary = plan.summary,
        endingState = legacyEndingState(plan),
        coveredTitles = plan.coveredTitles,
        coveredNodeIds = plan.coveredNodeIds,
        unverifiedClaims = plan.unverifiedClaims,
        verificationNote = plan.verificationNote,
        createdAt = now,
        updatedAt = now,
    )

    /**
     * 旧备份里 `carryOver` 是双重语义列：已发布章节装的是结尾状态，未发布章节装的是开头钩子。
     *
     * 这个函数和 [legacyOpeningHook] 是全项目唯一允许读那一列的两处（3D）——
     * 它们处理的正是那一列还有含义的旧数据，规则与 v5→v6 / v6→v7 迁移逐字一致。
     * 集中在这里也让「业务代码不读 carryOver」这条约束可以被测试检查。
     */
    @Suppress("DEPRECATION")
    private fun legacyEndingState(plan: ChapterPlan): String =
        if (plan.isReadable) plan.carryOver else ""

    @Suppress("DEPRECATION")
    private fun legacyOpeningHook(plan: ChapterPlan): String =
        if (plan.isReadable) "" else plan.carryOver

    /**
     * 把导入的引用关系整理成「每条引用都挂在真实存在的块上、
     * 解析不到片段的一律记成未确认」（4A）。
     *
     * 三种输入：
     * - v7 备份：自带 citations.json，按导入值重建，只重新判定 chunkId 是否仍然存在；
     * - v1~v6 备份：没有引用表，按块的 sourceRefs 重建，与 v9→v10 迁移同一条规则；
     * - 改坏的备份：引用指向不存在的块 → 丢掉（那条引用已经没有归属了）；
     *   引用指向不存在的片段 → **不丢**，记成未确认。
     *
     * 两者的区别值得说清楚：块不存在意味着这条引用连「哪句话需要出处」都答不上来，
     * 留着没有任何信息量；片段不存在则仍然在说一件真事——
     * 「这段话当初有出处，现在对不上了」，那正是未确认状态要表达的。
     *
     * 引用 ID 一律用 [Citation.idFor] 重算，不沿用备份里的值：
     * 同一份数据从迁移、导入、重新生成三条路径进来必须得到同一个 ID，
     * 否则同一条引用会在库里躺两份。
     */
    private fun normalizeCitations(
        blocks: List<ChapterBlock>,
        imported: List<Citation>,
        knownChunkIds: Set<String>,
    ): NormalizedCitations {
        val byBlock = imported.groupBy { it.blockId }
        val citations = mutableListOf<Citation>()
        val rebuiltBlocks = blocks.map { block ->
            val refs = byBlock[block.id]
                ?.sortedBy { it.order }
                // 引用标识：优先原样保留的 rawRef，其次导入值里的 chunkId。
                // 旧的 UNCONFIRMED 引用只有 rawRef，新的 RESOLVED 引用两者都有
                ?.map { it.rawRef.ifBlank { it.chunkId.orEmpty() } }
                ?: block.sourceRefs
            val resolved = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            var order = 0
            refs.map { it.trim() }.filter { it.isNotEmpty() }.forEach { ref ->
                if (!seen.add(ref)) return@forEach
                val known = ref in knownChunkIds
                if (known) resolved += ref
                citations += Citation(
                    id = Citation.idFor(block.id, order),
                    projectId = block.projectId,
                    chapterId = block.chapterId,
                    // 引用属于块所在的那一版。备份里的 versionId 可能因为块被重挂而过时
                    versionId = block.versionId,
                    blockId = block.id,
                    chunkId = if (known) ref else null,
                    rawRef = ref,
                    status = if (known) CitationStatus.RESOLVED else CitationStatus.UNCONFIRMED,
                    order = order,
                )
                order += 1
            }
            block.copy(sourceRefs = resolved)
        }
        return NormalizedCitations(blocks = rebuiltBlocks, citations = citations)
    }

    /**
     * 整理导入进来的核验问题（4B）。
     *
     * 两条判断，理由和 [normalizeCitations] 同源但结论不同：
     *
     * - **版本不存在 → 丢掉。** 问题是针对某一版正文提的，那一版不在了，
     *   这条问题连「说的是哪份文字」都答不上来。
     * - **blockId 对不上那一版真实的块 → 不丢，降级成「定位不到具体段落」。**
     *   问题本身仍然成立（核验当时确实指出了它），只是没法再单独重写那一段。
     *   丢掉它等于悄悄告诉用户「这一章没问题」。
     *
     * ID 一律用 [VerificationIssue.idFor] 重算，与迁移、重新核验三条路径一致。
     */
    private fun normalizeIssues(
        imported: List<VerificationIssue>,
        blocks: List<ChapterBlock>,
        versionIds: Set<String>,
    ): List<VerificationIssue> {
        val blockToVersion = blocks.associate { it.id to it.versionId }
        return imported
            .filter { it.versionId in versionIds && it.message.isNotBlank() }
            .groupBy { it.versionId }
            .flatMap { (versionId, group) ->
                group.sortedBy { it.order }.mapIndexed { order, issue ->
                    // 块必须存在，而且必须属于同一版正文。属于别的版本的 blockId
                    // 拿到这一版上是个错误的坐标——点开会看到一段不相干的文字
                    val blockId = issue.blockId
                        ?.takeIf { blockToVersion[it] == versionId }
                    issue.copy(
                        id = VerificationIssue.idFor(versionId, order),
                        blockId = blockId,
                        order = order,
                    )
                }
            }
    }

    private class NormalizedCitations(
        val blocks: List<ChapterBlock>,
        val citations: List<Citation>,
    )

    private class VersionedChapters(
        val plans: List<ChapterPlan>,
        val versions: List<ChapterVersion>,
        val blocks: List<ChapterBlock>,
        val versionIds: Set<String>,
        val activeVersionOf: Map<String, String>,
    )

    /** 单事务内先清理同项目历史数据再写入，避免残留旧子数据（B14/N7） */
    private suspend fun writeAtomically(payload: ImportPayload) {
        container.inTransaction {
            payload.projects.forEach { project ->
                container.sourceRepository.deleteByProject(project.id)
                container.knowledgeRepository.clearForProject(project.id)
                container.citationRepository.deleteByProject(project.id)
                container.issueRepository.deleteByProject(project.id)
                container.chapterRepository.clearBlocksForProject(project.id)
                container.chapterRepository.clearVersionsForProject(project.id)
                container.chapterRepository.clearPlansForProject(project.id)
                container.quizRepository.deleteByProject(project.id)
                container.reviewRepository.clearForProject(project.id)
                container.progressRepository.clearForProject(project.id)
                container.storyBibleRepository.clearForProject(project.id)
                container.projectRepository.create(project)
            }

            payload.sources.forEach { source ->
                container.sourceRepository.addSource(
                    source,
                    payload.chunks.filter { it.sourceId == source.id },
                )
            }
            if (payload.knowledge.isNotEmpty()) container.knowledgeRepository.upsertAll(payload.knowledge)
            if (payload.plans.isNotEmpty()) container.chapterRepository.upsertPlans(payload.plans)
            // 版本行先于正文块写入，保证「块 → 版本 → 章节」这条链在事务内始终自洽
            if (payload.versions.isNotEmpty()) container.chapterRepository.upsertVersions(payload.versions)
            if (payload.blocks.isNotEmpty()) container.chapterRepository.upsertBlocks(payload.blocks)
            // 引用在块之后写入，同一个事务内「引用 → 块 → 版本 → 章节」这条链始终自洽
            if (payload.citations.isNotEmpty()) container.citationRepository.upsertAll(payload.citations)
            // 问题在块之后写入，同理：它引用的 blockId 此刻已经在库里了
            if (payload.issues.isNotEmpty()) container.issueRepository.upsertAll(payload.issues)
            if (payload.quizzes.isNotEmpty()) container.quizRepository.upsertAll(payload.quizzes)
            payload.answers.forEach { container.quizRepository.recordAnswer(it) }
            if (payload.reviews.isNotEmpty()) container.reviewRepository.upsertAll(payload.reviews)
            payload.progress.forEach { container.progressRepository.save(it) }
            payload.bibles.forEach { container.storyBibleRepository.save(it) }
        }
    }

    private inline fun <reified T> decodeList(files: Map<String, String>, name: String): List<T> =
        files[name]?.let { json.decodeFromString<List<T>>(it) } ?: emptyList()

    private data class ImportPayload(
        val projects: List<Project>,
        val sources: List<SourceDocument>,
        val chunks: List<SourceChunk>,
        val knowledge: List<KnowledgeNode>,
        val plans: List<ChapterPlan>,
        val versions: List<ChapterVersion>,
        val blocks: List<ChapterBlock>,
        val citations: List<Citation>,
        val issues: List<VerificationIssue>,
        val quizzes: List<Quiz>,
        val answers: List<AnswerRecord>,
        val reviews: List<ReviewItem>,
        val progress: List<ReadingProgress>,
        val bibles: List<StoryBible>,
    )

    companion object {
        // v7：加入 citations.json（正文块 ↔ 资料片段的引用关系），
        //     来源带上法域/生效时间/法律版本，片段带上页码/章节/段落序号（第四阶段 4A）。
        // v6：版本状态区分 ARCHIVED / ABANDONED，覆盖关系带上 coveredNodeIds，
        //     阅读进度带上「正文换过版本」的痕迹（第三阶段 3D）。
        // v5：章节带上分卷字段、故事总纲带上 currentVolumeIndex（3C）。
        // v4 加入 chapter_versions.json 与 versionId（3A）。
        // 仍能读 v1~v5 备份——缺失的版本信息按「每章一个初始版本」补齐，
        // 缺失的分卷字段一律落在第一卷，缺失的 coveredNodeIds 按标题唯一匹配补齐，
        // v5 里被取代的旧正文（记成 ABANDONED）按与 v8→v9 迁移相同的判据改成 ARCHIVED。
        // 仍能读 v1~v6 备份：没有 citations.json 时按块的 sourceRefs 重建引用，
        // 缺失的来源元数据与片段定位一律留空（旧片段的页码无从倒推，硬填会让用户翻错页）。
        // v8：加入 verification_issues.json（核验问题逐条记录），
        //     chapter_versions 带上 derivedFromVersionId / rewrittenBlockIds（第四阶段 4B）。
        // 仍能读 v1~v7 备份：没有 verification_issues.json 时这张表就是空的——
        // 旧备份里的问题当年被拼成一句 verificationNote 存着，
        // 没有 blockId 也没有原始类型，回填只会造出一批「定位不到段落」的假记录，
        // 让「本章还有 3 个问题待处理」从一开始就不可信。那句说明照原样保留。
        const val FORMAT_VERSION = 8
        const val MAX_ENTRIES = 64
        const val MAX_ENTRY_BYTES = 32 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        private const val READ_BUFFER = 8 * 1024
    }
}

@kotlinx.serialization.Serializable
data class TransferManifest(
    val app: String,
    val formatVersion: Int,
    val exportedAt: Long,
    val projects: Int,
)
