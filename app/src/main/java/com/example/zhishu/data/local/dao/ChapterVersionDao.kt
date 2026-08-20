package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.QuizState

/**
 * 正文版本表的读写（第三阶段 3A）。
 *
 * 这里同时声明了对 chapters 表的两条更新，是为了让「发布新版本」能落在**一个** Room 事务里：
 * 版本置为 PUBLISHED、旧版本置为 ABANDONED、章节的 activeVersionId 改指向，
 * 三件事必须一起成或一起不成——中间断电就会出现「章节指向一个还没发布的版本」。
 */
@Dao
interface ChapterVersionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(version: ChapterVersion)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(versions: List<ChapterVersion>)

    @Query("SELECT * FROM chapter_versions WHERE id = :id")
    suspend fun getById(id: String): ChapterVersion?

    @Query("SELECT * FROM chapter_versions WHERE chapterId = :chapterId ORDER BY versionNumber")
    suspend fun getByChapter(chapterId: String): List<ChapterVersion>

    @Query("SELECT * FROM chapter_versions WHERE projectId = :projectId ORDER BY chapterId, versionNumber")
    suspend fun getByProject(projectId: String): List<ChapterVersion>

    /** 当前生效的版本。章节没有 activeVersionId 时返回 null，表示这一章还没有可读正文。 */
    @Query(
        "SELECT chapter_versions.* FROM chapter_versions " +
            "JOIN chapters ON chapters.activeVersionId = chapter_versions.id " +
            "WHERE chapters.id = :chapterId",
    )
    suspend fun getActive(chapterId: String): ChapterVersion?

    /** 下一个版本号。没有历史版本时返回 1。 */
    @Query("SELECT COALESCE(MAX(versionNumber), 0) FROM chapter_versions WHERE chapterId = :chapterId")
    suspend fun maxVersionNumber(chapterId: String): Int

    @Query(
        "UPDATE chapter_versions SET status = :status, verificationNote = :note, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun updateStatus(
        id: String,
        status: ChapterVersionStatus,
        note: String,
        updatedAt: Long,
    )

    /**
     * 只在版本仍未发布时放弃它（取消 / 核验失败收尾用）。
     *
     * 条件更新的理由和章节状态一样：不能把一个已经发布并成为 activeVersion 的版本
     * 反手改成 ABANDONED——那会让整章正文从阅读器里消失。
     */
    @Query(
        "UPDATE chapter_versions SET status = :status, verificationNote = :note, updatedAt = :updatedAt " +
            "WHERE id = :id AND status IN (:onlyIf)",
    )
    suspend fun updateStatusIfIn(
        id: String,
        status: ChapterVersionStatus,
        note: String,
        updatedAt: Long,
        onlyIf: List<ChapterVersionStatus>,
    ): Int

    /**
     * 原子发布：版本置 PUBLISHED、旧发布版本置 ARCHIVED、未完成的旧尝试置 ABANDONED、
     * 章节指针改指向新版本。
     *
     * 归档与放弃必须分开（3D）：旧的那一版是**成功过**的正文，读者真的读过它，
     * 只是现在有了更新的一版；而半途死掉的草稿从来没通过核验。
     * 都写成 ABANDONED 的话，「上次重新生成失败了」这种提示会把每一次
     * 成功重生成留下的旧版本也报成失败。
     *
     * 章节的展示元数据（摘要、已覆盖知识点、待核实清单）跟着一起换，
     * 否则会出现「正文是新的，摘要还是旧的」。quizState 归零是因为
     * 旧版本的题问的是被替换掉的情节，不能算作新正文的自测题。
     *
     * 注意这里**不写 chapters.carryOver**：结尾状态属于这一版正文，
     * 存在 chapter_versions.endingState 上（3B）。
     */
    @Transaction
    suspend fun publishVersion(
        version: ChapterVersion,
        updatedAt: Long,
    ) {
        upsert(version.copy(status = ChapterVersionStatus.PUBLISHED, updatedAt = updatedAt))
        archiveSupersededVersions(
            chapterId = version.chapterId,
            keepId = version.id,
            archived = ChapterVersionStatus.ARCHIVED,
            published = ChapterVersionStatus.PUBLISHED,
            updatedAt = updatedAt,
        )
        abandonStalePendingVersions(
            chapterId = version.chapterId,
            keepId = version.id,
            abandoned = ChapterVersionStatus.ABANDONED,
            pending = PENDING_STATUSES,
            note = STALE_ATTEMPT_NOTE,
            updatedAt = updatedAt,
        )
        // 还没绑版本的历史题（旧备份导入的）在指针搬走之前先钉到上一版正文上。
        // 它们问的是那一版的情节，留着 versionId = '' 的话会被「当前版本的题」
        // 这条查询继续算进来，跟着新正文一起显示（3D）。一道题都不删。
        bindLegacyQuizzesToCurrentVersion(version.chapterId)
        activateVersion(
            chapterId = version.chapterId,
            versionId = version.id,
            status = ChapterStatus.PUBLISHED,
            summary = version.summary,
            coveredTitles = version.coveredTitles,
            coveredNodeIds = version.coveredNodeIds,
            unverifiedClaims = version.unverifiedClaims,
            note = version.verificationNote,
            quizState = QuizState.NONE,
        )
        // 阅读位置回到这一章的开头：旧的 blockId 属于上一版正文，
        // 在新正文里根本不存在，留着它只会让阅读器「恢复失败、停在顶部」而说不出为什么。
        // 章节本身保留，用户下次点「继续阅读」还是回到这一章。
        // bodyUpdatedAt 记一笔，阅读器据此给一次「本章已更新，从章首开始」的提示（3D）。
        resetProgressAnchor(version.chapterId, updatedAt)
    }

    @Query(
        "UPDATE reading_progress SET blockId = NULL, blockOffset = 0, bodyUpdatedAt = :updatedAt, " +
            // 提示重新计一次：这是一次**新的**更新，上一次看过的那条提示与它无关
            "progressNoticeShown = 0 WHERE chapterId = :chapterId",
    )
    suspend fun resetProgressAnchor(chapterId: String, updatedAt: Long)

    /**
     * 把这一章还没绑定版本的历史题绑到**当前**发布版本上（3D）。
     *
     * 只在指针即将搬走之前调用。`versionId = ''` 的题是旧备份导入的，
     * 「当前版本的题」这条查询为了兼容它们放行了空串——一旦发布新版本，
     * 它们就会挂到新正文上，问的却是被替换掉的情节。绑到旧版本即可，一道题不删。
     */
    @Query(
        "UPDATE quizzes SET versionId = COALESCE(" +
            "(SELECT activeVersionId FROM chapters WHERE chapters.id = :chapterId), '') " +
            "WHERE chapterId = :chapterId AND versionId = '' " +
            "AND (SELECT activeVersionId FROM chapters WHERE chapters.id = :chapterId) IS NOT NULL",
    )
    suspend fun bindLegacyQuizzesToCurrentVersion(chapterId: String)

    /** 上一版正文：成功过、被取代 → 归档。只动 PUBLISHED，不碰历史归档与放弃记录 */
    @Query(
        "UPDATE chapter_versions SET status = :archived, updatedAt = :updatedAt " +
            "WHERE chapterId = :chapterId AND id != :keepId AND status = :published",
    )
    suspend fun archiveSupersededVersions(
        chapterId: String,
        keepId: String,
        archived: ChapterVersionStatus,
        published: ChapterVersionStatus,
        updatedAt: Long,
    )

    /**
     * 同章还停在 DRAFT / VERIFYING 的旧尝试：本次发布之后它们永远不会再被走完，
     * 直接作废并写明原因，免得留成一堆看不出结局的僵尸版本。
     */
    @Query(
        "UPDATE chapter_versions SET status = :abandoned, verificationNote = :note, updatedAt = :updatedAt " +
            "WHERE chapterId = :chapterId AND id != :keepId AND status IN (:pending)",
    )
    suspend fun abandonStalePendingVersions(
        chapterId: String,
        keepId: String,
        abandoned: ChapterVersionStatus,
        pending: List<ChapterVersionStatus>,
        note: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE chapters SET activeVersionId = :versionId, status = :status, summary = :summary, " +
            "coveredTitles = :coveredTitles, coveredNodeIds = :coveredNodeIds, " +
            "unverifiedClaims = :unverifiedClaims, " +
            "verificationNote = :note, quizState = :quizState, quizNote = '' WHERE id = :chapterId",
    )
    suspend fun activateVersion(
        chapterId: String,
        versionId: String,
        status: ChapterStatus,
        summary: String,
        coveredTitles: List<String>,
        coveredNodeIds: List<String>,
        unverifiedClaims: List<String>,
        note: String,
        quizState: QuizState,
    )

    @Query("DELETE FROM chapter_versions WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: String)

    @Query("DELETE FROM chapter_versions WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    @Query("SELECT COUNT(*) FROM chapter_versions WHERE chapterId = :chapterId")
    suspend fun countByChapter(chapterId: String): Int

    companion object {
        /** 还没走完的版本状态 */
        val PENDING_STATUSES = listOf(
            ChapterVersionStatus.DRAFT,
            ChapterVersionStatus.VERIFYING,
        )

        /** 别的版本发布之后，还停在半路的那次尝试的结局说明 */
        const val STALE_ATTEMPT_NOTE = "这一版没有写完，已被后来发布的版本取代。"
    }
}
