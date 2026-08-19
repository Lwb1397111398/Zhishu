package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceChunkFts
import com.example.zhishu.search.SearchTokenizer

@Dao
interface SourceChunkDao {

    @Transaction
    suspend fun upsertAll(chunks: List<SourceChunk>) {
        insertAll(chunks)
        // 同步维护 FTS 索引（P2）
        chunks.forEach { chunk ->
            deleteFtsForChunk(chunk.id)
            insertFts(
                SourceChunkFts(
                    chunkId = chunk.id,
                    projectId = chunk.projectId,
                    body = SearchTokenizer.indexBody(chunk.text),
                ),
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<SourceChunk>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entry: SourceChunkFts)

    @Query("DELETE FROM source_chunk_fts WHERE chunkId = :chunkId")
    suspend fun deleteFtsForChunk(chunkId: String)

    @Query("SELECT * FROM source_chunks WHERE sourceId = :sourceId ORDER BY ordinal")
    suspend fun getBySource(sourceId: String): List<SourceChunk>

    /** 按项目直接取片段，供流水线加载资料上下文（B1） */
    @Query("SELECT * FROM source_chunks WHERE projectId = :projectId ORDER BY sourceId, ordinal")
    suspend fun getByProject(projectId: String): List<SourceChunk>

    /** 按片段 ID 批量取，用于 sourceRefs 精确加载（B1） */
    @Query("SELECT * FROM source_chunks WHERE projectId = :projectId AND id IN (:ids) ORDER BY sourceId, ordinal")
    suspend fun getByIds(projectId: String, ids: List<String>): List<SourceChunk>

    @Transaction
    suspend fun deleteBySource(sourceId: String) {
        deleteFtsBySource(sourceId)
        deleteChunksBySource(sourceId)
    }

    @Query("DELETE FROM source_chunks WHERE sourceId = :sourceId")
    suspend fun deleteChunksBySource(sourceId: String)

    @Query(
        "DELETE FROM source_chunk_fts WHERE chunkId IN " +
            "(SELECT id FROM source_chunks WHERE sourceId = :sourceId)",
    )
    suspend fun deleteFtsBySource(sourceId: String)

    /** 走 FTS 倒排索引，避免前后通配符导致的全表扫描（P2） */
    @Query(
        "SELECT source_chunks.* FROM source_chunk_fts " +
            "JOIN source_chunks ON source_chunks.id = source_chunk_fts.chunkId " +
            "WHERE source_chunk_fts.projectId = :projectId AND source_chunk_fts.body MATCH :match " +
            "ORDER BY source_chunks.ordinal LIMIT 50",
    )
    suspend fun searchByProjectMatch(projectId: String, match: String): List<SourceChunk>

    @Transaction
    suspend fun deleteByProject(projectId: String) {
        deleteFtsByProject(projectId)
        deleteChunksByProject(projectId)
    }

    @Query("DELETE FROM source_chunks WHERE projectId = :projectId")
    suspend fun deleteChunksByProject(projectId: String)

    @Query("DELETE FROM source_chunk_fts WHERE projectId = :projectId")
    suspend fun deleteFtsByProject(projectId: String)

    @Query("SELECT COUNT(*) FROM source_chunks WHERE projectId = :projectId")
    suspend fun countByProject(projectId: String): Int

    @Query("SELECT COUNT(*) FROM source_chunk_fts WHERE projectId = :projectId")
    suspend fun countFtsByProject(projectId: String): Int
}
