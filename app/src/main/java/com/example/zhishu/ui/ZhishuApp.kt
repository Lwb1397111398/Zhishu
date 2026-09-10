package com.example.zhishu.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.zhishu.ui.screens.CreateProjectScreen
import com.example.zhishu.ui.screens.HomeScreen
import com.example.zhishu.ui.screens.KnowledgeHubScreen
import com.example.zhishu.ui.screens.ProjectDetailScreen
import com.example.zhishu.ui.screens.QuizScreen
import com.example.zhishu.ui.screens.ReaderScreen
import com.example.zhishu.ui.screens.ReviewScreen
import com.example.zhishu.ui.screens.SearchScreen
import com.example.zhishu.ui.screens.SettingsScreen
import com.example.zhishu.ui.screens.SourceDetailScreen
import com.example.zhishu.ui.screens.TrustCenterScreen

object Routes {
    const val HOME = "home"
    const val KNOWLEDGE = "knowledge"
    const val REVIEW = "review"
    const val SETTINGS = "settings"
    const val CREATE = "create"
    const val PROJECT = "project/{projectId}?nodeId={nodeId}&tab={tab}"
    const val READER = "reader/{chapterId}?blockId={blockId}"
    const val QUIZ = "quiz/{chapterId}"
    const val SEARCH = "search?projectId={projectId}"

    /** 资料详情：正文 → 资料的落点。[chunkId] 决定滚到哪个片段（4C） */
    const val SOURCE = "source/{sourceId}?chunkId={chunkId}"

    /** 未核实内容筛选（4C） */
    const val TRUST = "trust/{projectId}"

    /** [nodeId] 不为空时直接打开「知识主干」页并展开该知识点（正文 → 知识地图） */
    fun project(projectId: String, nodeId: String? = null, tab: Int = 0) =
        "project/$projectId?nodeId=${nodeId.orEmpty()}&tab=$tab"

    /** [blockId] 不为空时阅读器滚动到该正文块并短暂高亮（搜索/地图/资料 → 正文） */
    fun reader(chapterId: String, blockId: String? = null) =
        "reader/$chapterId?blockId=${blockId.orEmpty()}"

    fun quiz(chapterId: String) = "quiz/$chapterId"
    fun search(projectId: String? = null) =
        if (projectId == null) "search?projectId=" else "search?projectId=$projectId"

    /**
     * 资料详情，可带回跳片段（4C）。
     *
     * 只用 sourceId + chunkId 两个稳定锚点，不带 versionId：这条链接指向的是资料，
     * 和正文哪一版无关。片段 ID 在重新解析同一份资料时保持稳定（内容哈希 + 序号），
     * 真的对不上时页面会如实说「那个片段已经不在这份资料里了」。
     */
    fun source(sourceId: String, chunkId: String? = null) =
        "source/$sourceId?chunkId=${chunkId.orEmpty()}"

    fun trust(projectId: String) = "trust/$projectId"
}

private data class TabItem(val route: String, val label: String, val icon: ImageVector)

/**
 * 底部导航只留主线三项。
 *
 * 「复习」不再是一级入口——它是可选辅助，摆在这里会让人觉得读完还有作业没交。
 * 页面与数据都保留，改从「我的 → 复习中心」进入。
 */
private val topTabs = listOf(
    TabItem(Routes.HOME, "学习", Icons.Default.Home),
    TabItem(Routes.KNOWLEDGE, "知识", Icons.AutoMirrored.Filled.List),
    TabItem(Routes.SETTINGS, "我的", Icons.Default.Settings),
)

@Composable
fun ZhishuRoot(
    /** 点通知进来时要直接打开的项目（第二阶段）。为空就走正常首页。 */
    initialProjectId: String? = null,
    /**
     * 通知点击序号。同一项目的通知点第二次时 [initialProjectId] 不变，
     * 光靠它做 key 的 LaunchedEffect 不会重跑——用户已经导航去别处就跳不回来。
     * 每次点击自增，保证「点通知 → 打开项目」每次都生效。
     */
    projectNavToken: Int = 0,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = topTabs.any { it.route == currentRoute }

    // 只在这一次进入时跳一次，之后用户自己怎么导航就不再干预。
    // launchSingleTop：配置变更（旋转等）会重建组合、LaunchedEffect 重跑，
    // 不加这道锁会把同一个项目页重复压进返回栈。
    LaunchedEffect(initialProjectId, projectNavToken) {
        if (!initialProjectId.isNullOrBlank()) {
            navController.navigate(Routes.project(initialProjectId)) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                ZhishuBottomBar(navController = navController, currentRoute = currentRoute)
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenProject = { navController.navigate(Routes.project(it)) },
                    onCreateProject = { navController.navigate(Routes.CREATE) },
                    onOpenReader = { chapterId, blockId ->
                        navController.navigate(Routes.reader(chapterId, blockId))
                    },
                    onGoReview = { navController.navigate(Routes.REVIEW) },
                )
            }
            composable(Routes.KNOWLEDGE) {
                KnowledgeHubScreen(
                    // 知识页的卡片语义就是「看这本书的知识地图」，直达对应 tab
                    onOpenMap = { navController.navigate(Routes.project(it, tab = 1)) },
                    onSearch = { navController.navigate(Routes.search()) },
                )
            }
            composable(Routes.REVIEW) {
                ReviewScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { navController.navigate(Routes.reader(it)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onOpenReview = { navController.navigate(Routes.REVIEW) })
            }
            composable(Routes.CREATE) {
                CreateProjectScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { projectId ->
                        navController.navigate(Routes.project(projectId)) {
                            popUpTo(Routes.HOME)
                        }
                    },
                )
            }
            composable(
                route = Routes.PROJECT,
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("nodeId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("tab") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                ),
            ) { entry ->
                val projectId = entry.arguments?.getString("projectId").orEmpty()
                ProjectDetailScreen(
                    projectId = projectId,
                    initialTab = entry.arguments?.getInt("tab") ?: 0,
                    focusNodeId = entry.arguments?.getString("nodeId")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() },
                    onOpenReader = { chapterId, blockId ->
                        navController.navigate(Routes.reader(chapterId, blockId))
                    },
                    onOpenQuiz = { navController.navigate(Routes.quiz(it)) },
                    onOpenSearch = { navController.navigate(Routes.search(it)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenSource = { navController.navigate(Routes.source(it)) },
                    onOpenTrustCenter = { navController.navigate(Routes.trust(it)) },
                )
            }
            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("chapterId") { type = NavType.StringType },
                    navArgument("blockId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val chapterId = entry.arguments?.getString("chapterId").orEmpty()
                ReaderScreen(
                    chapterId = chapterId,
                    targetBlockId = entry.arguments?.getString("blockId")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { navController.navigate(Routes.reader(it)) },
                    onOpenQuiz = { navController.navigate(Routes.quiz(it)) },
                    onOpenNodeInMap = { projectId, nodeId ->
                        navController.navigate(Routes.project(projectId, nodeId))
                    },
                    onOpenSource = { sourceId, chunkId ->
                        navController.navigate(Routes.source(sourceId, chunkId))
                    },
                    onOpenTrustCenter = { navController.navigate(Routes.trust(it)) },
                )
            }
            composable(
                route = Routes.QUIZ,
                arguments = listOf(navArgument("chapterId") { type = NavType.StringType }),
            ) { entry ->
                val chapterId = entry.arguments?.getString("chapterId").orEmpty()
                QuizScreen(chapterId = chapterId, onBack = { navController.popBackStack() })
            }
            composable(
                route = Routes.SEARCH,
                arguments = listOf(
                    navArgument("projectId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val projectId = entry.arguments?.getString("projectId")
                SearchScreen(
                    initialProjectId = projectId,
                    onBack = { navController.popBackStack() },
                    onOpenReader = { chapterId, blockId ->
                        navController.navigate(Routes.reader(chapterId, blockId))
                    },
                )
            }
            composable(
                route = Routes.SOURCE,
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("chunkId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                SourceDetailScreen(
                    sourceId = entry.arguments?.getString("sourceId").orEmpty(),
                    targetChunkId = entry.arguments?.getString("chunkId")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() },
                    onOpenReader = { chapterId, blockId ->
                        navController.navigate(Routes.reader(chapterId, blockId))
                    },
                )
            }
            composable(
                route = Routes.TRUST,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            ) { entry ->
                TrustCenterScreen(
                    projectId = entry.arguments?.getString("projectId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenReader = { chapterId, blockId ->
                        navController.navigate(Routes.reader(chapterId, blockId))
                    },
                )
            }
        }
    }
}

@Composable
private fun ZhishuBottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar {
        topTabs.forEach { tab ->
            val selected = currentRoute == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
