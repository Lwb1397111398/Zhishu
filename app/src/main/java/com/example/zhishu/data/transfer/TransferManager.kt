package com.example.zhishu.data.transfer

import android.content.Context
import android.net.Uri
import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.AnswerRecord
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.StoryBible
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
            val blocks = mutableListOf<ChapterBlock>()
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
                projectPlans.forEach { plan -> blocks += container.chapterRepository.getBlocks(plan.id) }
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
            entries["blocks.json"] = json.encodeToString(blocks)
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
        val blocks = decodeList<ChapterBlock>(files, "blocks.json")
            .filter { it.chapterId in planProject.keys }
            .map { block ->
                if (block.projectId.isBlank()) {
                    block.copy(projectId = planProject[block.chapterId].orEmpty())
                } else {
                    block
                }
            }
        val planIds = planProject.keys
        val quizzes = decodeList<Quiz>(files, "quizzes.json")
            .filter { it.projectId in projectIds && it.chapterId in planIds }
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

        return ImportPayload(
            projects = projects,
            sources = sources,
            chunks = chunks,
            knowledge = knowledge,
            plans = plans,
            blocks = blocks,
            quizzes = quizzes,
            answers = answers,
            reviews = reviews,
            progress = progress,
            bibles = bibles,
        )
    }

    /** 单事务内先清理同项目历史数据再写入，避免残留旧子数据（B14/N7） */
    private suspend fun writeAtomically(payload: ImportPayload) {
        container.inTransaction {
            payload.projects.forEach { project ->
                container.sourceRepository.deleteByProject(project.id)
                container.knowledgeRepository.clearForProject(project.id)
                container.chapterRepository.clearBlocksForProject(project.id)
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
            if (payload.blocks.isNotEmpty()) container.chapterRepository.upsertBlocks(payload.blocks)
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
        val blocks: List<ChapterBlock>,
        val quizzes: List<Quiz>,
        val answers: List<AnswerRecord>,
        val reviews: List<ReviewItem>,
        val progress: List<ReadingProgress>,
        val bibles: List<StoryBible>,
    )

    companion object {
        // v3：备份内加入 story_bibles.json（故事总纲 + 滚动记忆）。
        // 仍能读 v1/v2 备份——缺失的文件按空列表处理。
        const val FORMAT_VERSION = 3
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
