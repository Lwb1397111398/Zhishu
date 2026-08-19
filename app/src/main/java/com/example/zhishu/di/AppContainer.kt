package com.example.zhishu.di

import android.content.Context
import com.example.zhishu.ai.AssetsPromptProvider
import com.example.zhishu.ai.ConfiguredModelRouter
import com.example.zhishu.ai.pipeline.PipelineService
import com.example.zhishu.data.local.AppDatabase
import com.example.zhishu.data.local.repo.DefaultChapterRepository
import com.example.zhishu.data.local.repo.DefaultKnowledgeRepository
import com.example.zhishu.data.local.repo.DefaultProgressRepository
import com.example.zhishu.data.local.repo.DefaultProjectRepository
import com.example.zhishu.data.local.repo.DefaultQuizRepository
import com.example.zhishu.data.local.repo.DefaultReviewRepository
import com.example.zhishu.data.local.repo.DefaultSettingsRepository
import com.example.zhishu.data.local.repo.DefaultSourceRepository
import com.example.zhishu.data.local.repo.DefaultStoryBibleRepository
import com.example.zhishu.data.prefs.LearningPrefs
import com.example.zhishu.data.transfer.TransferManager
import com.example.zhishu.domain.repository.ChapterRepository
import com.example.zhishu.domain.repository.KnowledgeRepository
import com.example.zhishu.domain.repository.ProgressRepository
import com.example.zhishu.domain.repository.ProjectRepository
import com.example.zhishu.domain.repository.QuizRepository
import com.example.zhishu.domain.repository.ReviewRepository
import com.example.zhishu.domain.repository.SettingsRepository
import com.example.zhishu.domain.repository.SourceRepository
import com.example.zhishu.domain.repository.StoryBibleRepository
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
        dao = database.knowledgeNodeDao(),
        gapDao = database.knowledgeGapDao(),
        conflictDao = database.knowledgeConflictDao(),
    )
    val chapterRepository: ChapterRepository = DefaultChapterRepository(
        planDao = database.chapterPlanDao(),
        blockDao = database.chapterBlockDao(),
        versionDao = database.chapterVersionDao(),
    )
    val storyBibleRepository: StoryBibleRepository = DefaultStoryBibleRepository(database.storyBibleDao())
    val quizRepository: QuizRepository = DefaultQuizRepository(database.quizDao(), database.answerRecordDao())
    val reviewRepository: ReviewRepository = DefaultReviewRepository(database.reviewItemDao())
    val progressRepository: ProgressRepository = DefaultProgressRepository(database.readingProgressDao())
    val settingsRepository: SettingsRepository = DefaultSettingsRepository(database.modelConfigDao())

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
    )

    val pipeline: PipelineService = PipelineService(
        router = router,
        prompts = promptProvider,
        projectRepository = projectRepository,
        sourceRepository = sourceRepository,
        knowledgeRepository = knowledgeRepository,
        chapterRepository = chapterRepository,
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
}
