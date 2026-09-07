package com.example.zhishu.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 三档分工各自指向哪个 [ApiProfile]。
 *
 * `profileId` 为 null 表示该档还没选方案——生成时会被拦截并提示去设置页选择。
 * 指向的方案被删除时这一列会被清空（见 SettingsRepository.deleteProfile）；
 * 即便残留悬空引用，读取方按「取不到方案 = 未配置」处理，也是安全的。
 */
@Entity(tableName = "role_assignments")
data class RoleAssignment(
    @PrimaryKey val role: String,
    val profileId: Long?,
)
