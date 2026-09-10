package com.example.zhishu.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间戳 → 「yyyy-MM-dd」。此前在 3 个屏幕里各有一份同体私有副本 */
internal fun formatDay(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
