package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.StoryBible
import kotlinx.coroutines.flow.Flow

@Dao
interface StoryBibleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bible: StoryBible)

    @Query("SELECT * FROM story_bibles WHERE projectId = :projectId")
    suspend fun get(projectId: String): StoryBible?

    @Query("SELECT * FROM story_bibles WHERE projectId = :projectId")
    fun observe(projectId: String): Flow<StoryBible?>

    /** 只更新滚动记忆，不覆盖世界观等固定设定（N13 同源原则） */
    @Query(
        "UPDATE story_bibles SET runningState = :runningState, coveredTitles = :coveredTitles, " +
            "coveredNodeIds = :coveredNodeIds, " +
            "openThreads = :openThreads, updatedAt = :updatedAt WHERE projectId = :projectId",
    )
    suspend fun updateMemory(
        projectId: String,
        runningState: String,
        coveredTitles: List<String>,
        coveredNodeIds: List<String>,
        openThreads: List<String>,
        updatedAt: Long,
    )

    @Query("DELETE FROM story_bibles WHERE projectId = :projectId")
    suspend fun clearByProject(projectId: String)
}
