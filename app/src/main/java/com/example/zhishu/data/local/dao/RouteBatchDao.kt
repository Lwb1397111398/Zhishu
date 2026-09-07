package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.RouteBatch
import kotlinx.coroutines.flow.Flow

/**
 * 讲述路线批次生成的状态（三段式改造）。
 *
 * 一个项目同一时间只有一个进行中的路线批次；重新规划开新批次、旧批次整体作废。
 * 大纲、每章契约的状态都落到这一行上，供断点续跑：进程被杀或一章失败后，
 * 重跑只补 contractStatus != DONE 的章，大纲不重建。
 * 这是过程状态，不随备份导出。
 */
@Dao
interface RouteBatchDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(batch: RouteBatch)

    /** 取该项目最新的批次。没有批次时返回 null（从未规划 / 已清理） */
    @Query("SELECT * FROM route_batches WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatest(projectId: String): RouteBatch?

    /** 响应式版本：路线页靠它实时显示「几章契约已生成 / 几章失败」 */
    @Query("SELECT * FROM route_batches WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(projectId: String): Flow<RouteBatch?>

    @Query("SELECT * FROM route_batches WHERE id = :id")
    suspend fun getById(id: String): RouteBatch?

    @Query("DELETE FROM route_batches WHERE projectId = :projectId")
    suspend fun clearByProject(projectId: String)
}
