package com.example.zhishu.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class ModelRole {
    FAST,
    STORY,
    VERIFY,
    ;

    /** 面向用户的名字。错误提示里出现 "FAST" 对小白毫无意义（问题1） */
    val displayName: String
        get() = when (this) {
            FAST -> "知识梳理"
            STORY -> "正文写作"
            VERIFY -> "核验出题"
        }
}
