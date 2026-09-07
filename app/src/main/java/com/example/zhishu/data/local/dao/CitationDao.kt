package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.Citation
import com.example.zhishu.domain.model.CitationStatus
import kotlinx.coroutines.flow.Flow

/**
 * 正文块 ↔ 资料片段的引用关系（4A）。
 *
 * 命名沿用 3D 定下的规矩：**当前阅读**一律走 Active 系列（内联 chapters.activeVersionId），
 * 读历史版本的方法名里必须出现 ByVersion / AllVersions。
 * 一个含糊的 `getByChapter()` 会把三版正文的引用混在一起数给用户看，
 * 而且错了不会报错——只是「本章有 9 条来源」这种数字悄悄变得没有意义。
 */
@Dao
interface CitationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(citations: List<Citation>)

    // --- 当前版本（阅读、统计、回跳都用这些）---

    /** 当前 activeVersion 的全部引用，按正文顺序 */
    @Query(
        "SELECT citations.* FROM citations " +
            "JOIN chapters ON chapters.activeVersionId = citations.versionId " +
            "JOIN chapter_blocks ON chapter_blocks.id = citations.blockId " +
            "WHERE chapters.id = :chapterId ORDER BY chapter_blocks.[order], citations.[order]",
    )
    fun observeActiveByChapter(chapterId: String): Flow<List<Citation>>

    @Query(
        "SELECT citations.* FROM citations " +
            "JOIN chapters ON chapters.activeVersionId = citations.versionId " +
            "JOIN chapter_blocks ON chapter_blocks.id = citations.blockId " +
            "WHERE chapters.id = :chapterId ORDER BY chapter_blocks.[order], citations.[order]",
    )
    suspend fun getActiveByChapter(chapterId: String): List<Citation>

    /** 本章当前版本里，某个状态的引用条数。章节级来源摘要用（4C） */
    @Query(
        "SELECT COUNT(*) FROM citations " +
            "JOIN chapters ON chapters.activeVersionId = citations.versionId " +
            "WHERE chapters.id = :chapterId AND citations.status = :status",
    )
    suspend fun countActiveByChapterAndStatus(chapterId: String, status: CitationStatus): Int

    /**
     * 引用了某个资料片段的正文块（当前版本）。
     *
     * 「从资料片段定位到引用它的当前正文块」这条回跳方向就靠它。
     * 历史版本的块不能出现在结果里——点过去会打开一段读者看不到的正文。
     */
    @Query(
        "SELECT citations.* FROM citations " +
            "JOIN chapters ON chapters.activeVersionId = citations.versionId " +
            "WHERE citations.projectId = :projectId AND citations.chunkId = :chunkId " +
            "ORDER BY chapters.[order]",
    )
    suspend fun getActiveByChunk(projectId: String, chunkId: String): List<Citation>

    /** 全项目当前版本的引用。项目级统计与「未核实内容筛选」用（4C） */
    @Query(
        "SELECT citations.* FROM citations " +
            "JOIN chapters ON chapters.activeVersionId = citations.versionId " +
            "WHERE citations.projectId = :projectId ORDER BY chapters.[order], citations.[order]",
    )
    suspend fun getActiveByProject(projectId: String): List<Citation>

    // --- 指定版本 / 全部历史版本 ---

    /** 指定版本的引用，与章节当前指针无关。核验与局部重写用 */
    @Query("SELECT * FROM citations WHERE versionId = :versionId ORDER BY [order]")
    suspend fun getByVersion(versionId: String): List<Citation>

    /** 全项目**全部历史版本**的引用。只用于导出备份 */
    @Query("SELECT * FROM citations WHERE projectId = :projectId ORDER BY chapterId, versionId, [order]")
    suspend fun getAllVersionsByProject(projectId: String): List<Citation>

    // --- 清理 ---

    /**
     * 换掉某一版的引用，别的版本一律保留。
     *
     * 重新生成同一版（例如 4B 的局部重写）时先清后写，避免上一次尝试的引用残留；
     * 历史版本的引用一条都不动——它们是那一版正文的一部分。
     */
    @Query("DELETE FROM citations WHERE versionId = :versionId")
    suspend fun deleteByVersion(versionId: String)

    @Query("DELETE FROM citations WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: String)

    @Query("DELETE FROM citations WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    /**
     * 资料被删除后，引用它的那些引用降级（4A，状态在 4C 细分）。
     *
     * 删掉这些引用行是错的：正文里那段话还在，它当初确实是有依据写出来的。
     * 直接删除会让那一段看起来「从来没打算给出处」，读者也就不会想到去核对。
     *
     * 状态写成 [CitationStatus.SOURCE_DELETED] 而不是 UNCONFIRMED：这一段的依据
     * 当初是对上过的，只是资料不在了。同时把资料标题抄进 `lostSourceTitle`——
     * 必须在 `sources` 那一行还在的时候抄，删完就再也查不到了，
     * 而「引用的资料已被删除」不说是哪一份，用户没法判断也没法补救。
     */
    @Query(
        "UPDATE citations SET status = :deleted, chunkId = NULL, " +
            "lostSourceTitle = COALESCE((SELECT title FROM sources WHERE id = :sourceId), '') " +
            "WHERE chunkId IN (SELECT id FROM source_chunks WHERE sourceId = :sourceId)",
    )
    suspend fun unconfirmForSource(sourceId: String, deleted: CitationStatus)
}
