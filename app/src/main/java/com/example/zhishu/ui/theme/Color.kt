package com.example.zhishu.ui.theme

import androidx.compose.ui.graphics.Color

// 配色取自 APP 图标：近黑的底、暖奶白的纸、朱橙的高亮。
// 这样应用打开后和桌面图标是同一套语言，而不是两个东西。

/** 朱橙——图标上那道高亮，用作主色 */
val Primary = Color(0xFFD84315)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFFFDBCF)
val OnPrimaryContainer = Color(0xFF3D0900)

/** 近黑——图标底色，用作强调与文字 */
val Secondary = Color(0xFF3A3532)
val OnSecondary = Color(0xFFFFFFFF)
val SecondaryContainer = Color(0xFFEFE6DC)
val OnSecondaryContainer = Color(0xFF231E1B)

/** 墨蓝——留给"需要留意"的信息，与朱橙的警示区分开 */
val Tertiary = Color(0xFF44617B)
val OnTertiary = Color(0xFFFFFFFF)
val TertiaryContainer = Color(0xFFD4E4F6)
val OnTertiaryContainer = Color(0xFF001E30)

/** 纸感底色。长文阅读用暖白，比纯白不刺眼 */
val Background = Color(0xFFFDFAF5)
val OnBackground = Color(0xFF1F1B18)
val Surface = Color(0xFFFDFAF5)
val OnSurface = Color(0xFF1F1B18)
val SurfaceVariant = Color(0xFFF1E9DF)
val OnSurfaceVariant = Color(0xFF52453D)
val Outline = Color(0xFF85736A)
val OutlineVariant = Color(0xFFE0D4C8)

val Error = Color(0xFFBA1A1A)
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFFFDAD6)
val OnErrorContainer = Color(0xFF410002)

/** 阅读器专用：更暖的纸色 */
val WarmWhite = Color(0xFFFBF6EC)
val OnWarmWhite = Color(0xFF201A12)

// 深色模式：图标的黑底直接成为背景，橙色提亮
val DarkPrimary = Color(0xFFFFB59D)
val DarkOnPrimary = Color(0xFF5F1600)
val DarkPrimaryContainer = Color(0xFF8A2B0C)
val DarkOnPrimaryContainer = Color(0xFFFFDBCF)
val DarkBackground = Color(0xFF16130F)
val DarkOnBackground = Color(0xFFEDE0D8)
val DarkSurface = Color(0xFF16130F)
val DarkOnSurface = Color(0xFFEDE0D8)
val DarkSurfaceVariant = Color(0xFF3A322C)
val DarkOnSurfaceVariant = Color(0xFFD6C6BB)
val DarkOutline = Color(0xFF9F8D82)
val DarkOutlineVariant = Color(0xFF52453D)
val DarkSecondaryContainer = Color(0xFF3A322C)
val DarkOnSecondaryContainer = Color(0xFFEFE6DC)
val DarkTertiaryContainer = Color(0xFF2C4863)
val DarkOnTertiaryContainer = Color(0xFFD4E4F6)
val DarkError = Color(0xFFFFB4AB)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
