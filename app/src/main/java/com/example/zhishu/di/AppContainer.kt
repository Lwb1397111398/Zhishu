package com.example.zhishu.di

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File
import com.example.zhishu.ai.AssetsPromptProvider
import com.example.zhishu.ai.ConfiguredModelRouter
import com.example.zhishu.ai.pipeline.PipelineService
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.data.local.repo.DefaultChapterRepository
import com.example.zhishu.data.local.repo.DefaultCitationRepository
import com.example.zhishu.data.local.repo.DefaultKnowledgeRepository
import com.example.zhishu.data.local.repo.DefaultProgressRepository
import com.example.zhishu.data.local.repo.DefaultProjectRepository
import com.example.zhishu.data.local.repo.DefaultQuizRepository
import com.example.zhishu.data.local.repo.DefaultReviewRepository
import com.example.zhishu.data.local.repo.DefaultSettingsRepository
import com.example.zhishu.data.local.repo.DefaultSourceRepository
import com.example.zhishu.data.local.repo.DefaultStoryBibleRepository
import com.example.zhishu.data.local.repo.DefaultVerificationIssueRepository
import com.example.zhishu.data.prefs.LearningPrefs
import com.example.zhishu.data.transfer.TransferManager
import com.example.zhishu.domain.repository.ChapterRepository
import com.example.zhishu.domain.repository.CitationRepository
import com.example.zhishu.domain.repository.KnowledgeRepository
import com.example.zhishu.domain.repository.ProgressRepository
import com.example.zhishu.domain.repository.ProjectRepository
import com.example.zhishu.domain.repository.QuizRepository
import com.example.zhishu.domain.repository.ReviewRepository
import com.example.zhishu.domain.repository.SettingsRepository
import com.example.zhishu.domain.repository.SourceRepository
import com.example.zhishu.domain.repository.StoryBibleRepository
import com.example.zhishu.domain.repository.VerificationIssueRepository
import com.example.zhishu.search.DefaultSearchRepository
import com.example.zhishu.search.ParserServiceImpl
import com.example.zhishu.search.SearchRepository
import com.example.zhishu.search.SourceParser
import com.example.zhishu.security.AndroidKeyStoreCipher
import androidx.room.withTransaction

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val database: AppDatabase = AppDatabase.build(appContext)
    private val keyCipher: AndroidKeyStoreCipher by lazy { AndroidKeyStoreCipher() }

    val projectRepository: ProjectRepository = DefaultProjectRepository(database.projectDao())
    val sourceRepository: SourceRepository = DefaultSourceRepository(database.sourceDocumentDao(), database.sourceChunkDao())
    val knowledgeRepository: KnowledgeRepository = DefaultKnowledgeRepository(
        db = database,
        dao = database.knowledgeNodeDao(),
        gapDao = database.knowledgeGapDao(),
        conflictDao = database.knowledgeConflictDao(),
        batchDao = database.knowledgeMapBatchDao(),
        routeBatchDao = database.routeBatchDao(),
    )
    val chapterRepository: ChapterRepository = DefaultChapterRepository(
        planDao = database.chapterPlanDao(),
        blockDao = database.chapterBlockDao(),
        versionDao = database.chapterVersionDao(),
    )
    /** 正文块 ↔ 资料片段的引用关系（4A） */
    val citationRepository: CitationRepository = DefaultCitationRepository(database.citationDao())
    val issueRepository: VerificationIssueRepository =
        DefaultVerificationIssueRepository(database.verificationIssueDao())
    val storyBibleRepository: StoryBibleRepository = DefaultStoryBibleRepository(database.storyBibleDao())
    val quizRepository: QuizRepository = DefaultQuizRepository(database.quizDao(), database.answerRecordDao())
    val reviewRepository: ReviewRepository = DefaultReviewRepository(database.reviewItemDao())
    val progressRepository: ProgressRepository = DefaultProgressRepository(database.readingProgressDao())
    val settingsRepository: SettingsRepository =
        DefaultSettingsRepository(database.apiProfileDao(), database.roleAssignmentDao())

    /** 学习方式偏好（间隔复习开关，默认关） */
    val learningPrefs: LearningPrefs = LearningPrefs(appContext)

    val searchRepository: SearchRepository = DefaultSearchRepository(
        knowledgeNodeDao = database.knowledgeNodeDao(),
        chapterBlockDao = database.chapterBlockDao(),
        sourceChunkDao = database.sourceChunkDao(),
    )

    val parser: SourceParser = ParserServiceImpl()

    private val promptProvider = AssetsPromptProvider(appContext)
    val router = ConfiguredModelRouter(
        settingsRepository = settingsRepository,
        keyCipherProvider = { keyCipher },
        // 生成卡住时界面只显示「已开始」，没有这份记录就只能靠猜
        diagnosticsContext = appContext,
    )

    val pipeline: PipelineService = PipelineService(
        router = router,
        prompts = promptProvider,
        projectRepository = projectRepository,
        sourceRepository = sourceRepository,
        knowledgeRepository = knowledgeRepository,
        chapterRepository = chapterRepository,
        citationRepository = citationRepository,
        issueRepository = issueRepository,
        storyBibleRepository = storyBibleRepository,
        quizRepository = quizRepository,
        reviewRepository = reviewRepository,
        spacedReviewEnabled = { learningPrefs.spacedReviewEnabled.value },
    )

    val transfer: TransferManager = TransferManager(this)

    fun encryptApiKey(plain: String): String =
        keyCipher.encrypt(ConfiguredModelRouter.KEY_ALIAS, plain)

    /** 供 ViewModel / TransferManager 做原子写入（N5/N6/N7） */
    suspend fun <T> inTransaction(block: suspend () -> T): T = database.withTransaction(block)

    /**
     * 启动预热：在后台线程打开数据库（建库或跑完迁移）。
     *
     * 两个目的：
     * 1. 迁移与 Compose 首帧并行，用户点开图标时数据库多半已就绪，首页查询不用等；
     * 2. 数据库文件损坏（断电、进程被杀在写盘中途）时在这里自愈——
     *    Room 打不开库的异常如果冒到首个查询的协程里，APP 会「一打开就闪退」且每次如此。
     *    自愈把损坏文件改名保留（不删，现场还在），然后重建空库，让 APP 至少能打开。
     */
    fun warmUpDatabase() {
        try {
            database.openHelper.writableDatabase
        } catch (e: SQLiteException) {
            recoverAfterOpenFailure(e)
        } catch (e: IllegalStateException) {
            // Room 的 schema 校验失败也走这里：真机上它和文件损坏一样表现为启动闪退循环
            recoverAfterOpenFailure(e)
        }
    }

    /**
     * 打不开库时的恢复：损坏文件连同 wal/shm 改名留存，再打开就是新建的空库。
     * 恢复失败（例如磁盘满）只记日志——此时查询仍会失败，但由 ViewModel 层兜底显示错误，
     * 不允许任何一个异常直接杀死进程。
     */
    private fun recoverAfterOpenFailure(cause: Exception) {
        Log.e(TAG, "数据库打开失败，保留现场并重建", cause)
        runCatching { database.close() }
        val dbFile = appContext.getDatabasePath(DATABASE_FILE_NAME)
        if (dbFile.exists()) {
            val stamp = System.currentTimeMillis()
            val kept = File(dbFile.parentFile, "$DATABASE_FILE_NAME.corrupt-$stamp")
            runCatching { dbFile.renameTo(kept) }
            listOf("-wal", "-shm").forEach { suffix ->
                val sidecar = File(dbFile.parentFile, "$DATABASE_FILE_NAME$suffix")
                if (sidecar.exists()) {
                    runCatching { sidecar.renameTo(File(dbFile.parentFile, kept.name + suffix)) }
                }
            }
        }
        runCatching { database.openHelper.writableDatabase }
            .onFailure { Log.e(TAG, "重建后仍无法打开数据库", it) }
    }

    private companion object {
        const val TAG = "ZhishuApp"
        const val DATABASE_FILE_NAME = "zhishu.db"
    }
}
