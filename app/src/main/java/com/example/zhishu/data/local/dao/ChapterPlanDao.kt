package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterPlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(plans: List<ChapterPlan>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: ChapterPlan)

    @Query("SELECT * FROM chapters WHERE projectId = :projectId ORDER BY [order]")
    fun observeByProject(projectId: String): Flow<List<ChapterPlan>>

    @Query("SELECT * FROM chapters WHERE projectId = :projectId ORDER BY [order]")
    suspend fun getByProject(projectId: String): List<ChapterPlan>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getById(id: String): ChapterPlan?

    @Query("DELETE FROM chapters WHERE projectId = :projectId")
    suspend fun clearByProject(projectId: String)

    // --- 字段级更新（N13）：用户可编辑字段与生成状态分离，避免整行 upsert 覆盖 Worker 写入的状态 ---

    @Query("UPDATE chapters SET title = :title, plotGoal = :plotGoal, learningGoals = :learningGoals, wordTarget = :wordTarget WHERE id = :id")
    suspend fun updateEditableFields(
        id: String,
        title: String,
        plotGoal: String,
        learningGoals: List<String>,
        wordTarget: Int,
    )

    @Query("UPDATE chapters SET nodeIds = :nodeIds WHERE id = :id")
    suspend fun updateNodeIds(id: String, nodeIds: List<String>)

    @Query("UPDATE chapters SET status = :status, verificationNote = :note WHERE id = :id")
    suspend fun updateStatus(id: String, status: ChapterStatus, note: String)

    @Query("UPDATE chapters SET status = :status, summary = :summary, verificationNote = '' WHERE id = :id")
    suspend fun updatePublished(id: String, status: ChapterStatus, summary: String)

    /**
     * 章节发布时一并写入滚动记忆字段（问题5）。
     * carryOver / coveredTitles 供下一章使用，unverifiedClaims 供无资料模式提示用户核对。
     */
    @Query(
        "UPDATE chapters SET status = :status, summary = :summary, carryOver = :carryOver, " +
            "coveredTitles = :coveredTitles, unverifiedClaims = :unverifiedClaims, " +
            "verificationNote = :note WHERE id = :id",
    )
    suspend fun updatePublishedWithMemory(
        id: String,
        status: ChapterStatus,
        summary: String,
        carryOver: String,
        coveredTitles: List<String>,
        unverifiedClaims: List<String>,
        note: String,
    )
}
