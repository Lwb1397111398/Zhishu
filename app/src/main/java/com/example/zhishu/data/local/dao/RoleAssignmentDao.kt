package com.example.zhishu.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.zhishu.domain.model.RoleAssignment

@Dao
interface RoleAssignmentDao {
    @Query("SELECT * FROM role_assignments")
    suspend fun getAll(): List<RoleAssignment>

    @Query("SELECT * FROM role_assignments WHERE role = :role")
    suspend fun getByRole(role: String): RoleAssignment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: RoleAssignment)

    /** 方案删除后清空指向它的分配，避免悬空引用 */
    @Query("UPDATE role_assignments SET profileId = NULL WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: Long)
}
