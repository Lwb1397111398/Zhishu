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

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM sources WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}
