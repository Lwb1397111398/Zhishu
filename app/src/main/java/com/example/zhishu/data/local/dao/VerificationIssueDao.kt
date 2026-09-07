package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.IssueResolution
import com.example.zhishu.domain.model.VerificationIssue
import kotlinx.coroutines.flow.Flow

/**
 * 核验问题的读写（4B）。
 *
 * 命名沿用 3D 的规矩：**当前正文**的问题走 Active 系列（内联 chapters.activeVersionId），
 * 读某一版的问题必须写明 ByVersion，读全部历史必须写明 AllVersions。
 *
 * 这里 Active 与 ByVersion 都要用，而且两者的场合完全不同：
 * 用户在大纲页看到的是**当前正文**还剩几个问题（Active）；
 * 局部重写在草稿版本上做，重新核验后要读**那一版**的问题（ByVersion），
 * 那一版此刻还不是 activeVersion。混用的后果是「修完了但页面显示还有问题」。
 */
@Dao
interface VerificationIssueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(issues: List<VerificationIssue>)

    // --- 当前正文的问题（大纲页、阅读器都用这些）---

    @Query(
        "SELECT verification_issues.* FROM verification_issues " +
            "JOIN chapters ON chapters.activeVersionId = verification_issues.versionId " +
            "WHERE chapters.id = :chapterId ORDER BY verification_issues.[order]",
    )
    fun observeActiveByChapter(chapterId: String): Flow<List<VerificationIssue>>

    @Query(
        "SELECT verification_issues.* FROM verification_issues " +
            "JOIN chapters ON chapters.activeVersionId = verification_issues.versionId " +
            "WHERE chapters.id = :chapterId ORDER BY verification_issues.[order]",
    )
    suspend fun getActiveByChapter(chapterId: String): List<VerificationIssue>

    /** 当前正文还没处理的问题条数。大纲页角标用 */
    @Query(
        "SELECT COUNT(*) FROM verification_issues " +
            "JOIN chapters ON chapters.activeVersionId = verification_issues.versionId " +
            "WHERE chapters.id = :chapterId AND verification_issues.resolution = :open",
    )
    suspend fun countActiveOpen(chapterId: String, open: IssueResolution): Int

    /** 全项目当前正文的问题。项目级统计与「未核实内容筛选」用 */
    @Query(
        "SELECT verification_issues.* FROM verification_issues " +
            "JOIN chapters ON chapters.activeVersionId = verification_issues.versionId " +
            "WHERE verification_issues.projectId = :projectId " +
            "ORDER BY chapters.[order], verification_issues.[order]",
    )
    fun observeActiveByProject(projectId: String): Flow<List<VerificationIssue>>

    @Query(
        "SELECT verification_issues.* FROM verification_issues " +
            "JOIN chapters ON chapters.activeVersionId = verification_issues.versionId " +
            "WHERE verification_issues.projectId = :projectId " +
            "ORDER BY chapters.[order], verification_issues.[order]",
    )
    suspend fun getActiveByProject(projectId: String): List<VerificationIssue>

    // --- 指定版本 / 全部历史版本 ---

    /**
     * 指定版本的问题，与章节当前指针无关。
     *
     * 局部重写的核心查询：草稿版本还没发布，用 Active 系列一条都查不到。
     */
    @Query("SELECT * FROM verification_issues WHERE versionId = :versionId ORDER BY [order]")
    suspend fun getByVersion(versionId: String): List<VerificationIssue>

    @Query("SELECT * FROM verification_issues WHERE versionId = :versionId ORDER BY [order]")
    fun observeByVersion(versionId: String): Flow<List<VerificationIssue>>

    /** 全项目**全部历史版本**的问题。只用于导出备份 */
    @Query(
        "SELECT * FROM verification_issues WHERE projectId = :projectId " +
            "ORDER BY chapterId, versionId, [order]",
    )
    suspend fun getAllVersionsByProject(projectId: String): List<VerificationIssue>

    // --- 状态更新 ---

    /**
     * 用户把一条问题标成「我认了，不改」。
     *
     * 只允许改 resolution 一列：问题的内容是核验的产出，用户不该能改写它，
     * 否则「这条问题当初说的是什么」就再也查不清了。
     */
    @Query("UPDATE verification_issues SET resolution = :resolution WHERE id = :id")
    suspend fun updateResolution(id: String, resolution: IssueResolution)

    /**
     * 把某一版里挂在这些块上的问题标成已修（4B）。
     *
     * 由「重写 → 重新核验 → 这条问题没再出现」推出来，不是用户手点的。
     * 用 blockId 列表而不是逐条 id：局部重写是按块进行的，
     * 一块上可能挂着好几条问题，它们的结局是同一个。
     */
    @Query(
        "UPDATE verification_issues SET resolution = :fixed " +
            "WHERE versionId = :versionId AND blockId IN (:blockIds)",
    )
    suspend fun markFixedForBlocks(versionId: String, blockIds: List<String>, fixed: IssueResolution)

    // --- 清理 ---

    /** 换掉某一版的问题，别的版本一律保留。重新核验同一版时先清后写 */
    @Query("DELETE FROM verification_issues WHERE versionId = :versionId")
    suspend fun deleteByVersion(versionId: String)

    @Query("DELETE FROM verification_issues WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: String)

    @Query("DELETE FROM verification_issues WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}
