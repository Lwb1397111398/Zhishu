package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.SourceDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceDocument)

    @Query("SELECT * FROM sources WHERE projectId = :projectId ORDER BY fetchedAt DESC")
    fun observeByProject(projectId: String): Flow<List<SourceDocument>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: String): SourceDocument?

    @Query("SELECT * FROM sources WHERE projectId = :projectId ORDER BY fetchedAt DESC")
    suspend fun getByProject(projectId: String): List<SourceDocument>

    @Query("SELECT * FROM sources WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): SourceDocument?

    /**
     * 只写用户填的那几栏来源元数据（4A）。
     *
     * 不用整行 upsert：contentHash、parseStatus、fetchedAt 是解析结果，
     * 让「编辑来源信息」这个动作有机会覆盖它们，等于允许一次手误把资料的
     * 解析状态改掉。用户能改的就只有这四栏。
     */
    @Query(
        "UPDATE sources SET versionLabel = :versionLabel, jurisdiction = :jurisdiction, " +
            "effectiveFrom = :effectiveFrom, legalVersion = :legalVersion WHERE id = :id",
    )
    suspend fun updateMeta(
        id: String,
        versionLabel: String?,
        jurisdiction: String?,
        effectiveFrom: String?,
        legalVersion: String?,
    )

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM sources WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}
