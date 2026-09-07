package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.zhishu.domain.model.ApiProfile

@Dao
interface ApiProfileDao {
    /** 新增方案，返回自增后的 id（id=0 的实体走这里） */
    @Insert
    suspend fun insert(profile: ApiProfile): Long

    /** 已有 id 的方案走整行更新 */
    @Update
    suspend fun update(profile: ApiProfile)

    @Query("SELECT * FROM api_profiles ORDER BY id")
    suspend fun getAll(): List<ApiProfile>

    @Query("SELECT * FROM api_profiles WHERE id = :id")
    suspend fun getById(id: Long): ApiProfile?

    @Query("DELETE FROM api_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM api_profiles")
    suspend fun count(): Int
}
