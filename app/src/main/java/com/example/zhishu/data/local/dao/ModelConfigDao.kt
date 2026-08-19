package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.ModelConfig

@Dao
interface ModelConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: ModelConfig)

    @Query("SELECT * FROM model_configs")
    suspend fun getAll(): List<ModelConfig>

    @Query("SELECT * FROM model_configs WHERE role = :role")
    suspend fun getByRole(role: String): ModelConfig?

    @Query("DELETE FROM model_configs")
    suspend fun clearAll()
}
