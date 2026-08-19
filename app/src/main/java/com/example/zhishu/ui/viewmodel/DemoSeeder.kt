package com.example.zhishu.ui.viewmodel

import com.example.zhishu.di.AppContainer
import com.example.zhishu.domain.model.BlockType
import com.example.zhishu.domain.model.ChapterBlock
import com.example.zhishu.domain.model.ChapterPlan
import com.example.zhishu.domain.model.ChapterStatus
import com.example.zhishu.domain.model.ChapterVersion
import com.example.zhishu.domain.model.ChapterVersionStatus
import com.example.zhishu.domain.model.KnowledgeNode
import com.example.zhishu.domain.model.ParseStatus
import com.example.zhishu.domain.model.Project
import com.example.zhishu.domain.model.ProjectStatus
import com.example.zhishu.domain.model.Quiz
import com.example.zhishu.domain.model.QuizType
import com.example.zhishu.domain.model.ReadingProgress
import com.example.zhishu.domain.model.ReviewItem
import com.example.zhishu.domain.model.ReviewStatus
import com.example.zhishu.domain.model.SourceChunk
import com.example.zhishu.domain.model.SourceDocument
import com.example.zhishu.domain.model.SourceKind
import com.example.zhishu.domain.model.StoryBible

object DemoSeeder {

    private const val PROJECT_ID = "demo-civil-law"
    private const val SOURCE_ID = "demo-source-civil-code"

    /** sourceRefs 保存的是片段 ID，与流水线约定一致（B19） */
    private const val CHUNK_ID = "demo-chunk-1"

    private fun demoVersionId(chapterId: String) = "$chapterId-v1"

    suspend fun seed(container: AppContainer) {
        if (container.projectRepository.get(PROJECT_ID) != null) return
        val now = System.currentTimeMillis()

        container.projectRepository.create(
            Project(
                id = PROJECT_ID,
                title = "民法基础：遗产继承案",
                topic = "民法典继承编基础",
                baseline = "完全不了解",
                depth = "入门",
                dailyMinutes = 30,
                targetDays = 14,
                genre = "围绕一个棘手案子展开",
                status = ProjectStatus.READY,
                createdAt = now,
                updatedAt = now,
                resolvedGenre = "围绕一桩遗产纠纷层层展开",
            ),
        )

        // 示例也带上故事总纲与滚动记忆，让用户第一眼就看到"AI 选的讲法"和"上下文固定"（问题5、6）
        container.storyBibleRepository.save(
            StoryBible(
                projectId = PROJECT_ID,
                world = "一套老城区的房子，牵出一桩没有子女的遗产纠纷。故事围绕它一步步推进。",
                conflict = "一个人去世后，他留下的财产该按什么规则、转移给谁？",
                style = "围绕一桩遗产纠纷层层展开",
                approach = "继承法的每条规则都是在'谁能拿、谁不能拿'的真实冲突里被检验的，所以用一桩案子最自然。",
                characters = listOf(
                    "林晓｜远房侄女｜被卷入继承纠纷的普通人",
                    "小周｜护工｜手握一份存疑的遗嘱",
                ),
                throughLine = listOf(
                    "人死亡 → 继承开始，要先弄清什么是遗产",
                    "有没有遗嘱 → 决定走遗嘱继承还是法定继承",
                    "遗嘱是否有效 → 无效则回到法定继承顺序",
                ),
                runningState = "林晓继承了姑母半套房；发现一份三年前立、但立嘱人当时已患病的遗嘱；庭审最终认定遗嘱无效，按法定继承处理。",
                coveredTitles = listOf("继承", "遗产范围", "遗嘱继承", "法定继承"),
                openThreads = emptyList(),
                updatedAt = now,
            ),
        )

        val sourceText = "民法典继承编节选：第一千一百二十一条规定继承从被继承人死亡时开始；第一千一百二十二条规定遗产是自然人死亡时遗留的个人合法财产；第一千一百二十七条规定第一顺序继承人为配偶、子女、父母，第二顺序为兄弟姐妹、祖父母、外祖父母；第一千一百二十三条规定有遗嘱的按照遗嘱继承办理；第一千一百四十三条规定无民事行为能力人所立的遗嘱无效，受欺诈胁迫所立的遗嘱无效，伪造的遗嘱无效。"
        container.sourceRepository.addSource(
            SourceDocument(
                id = SOURCE_ID,
                projectId = PROJECT_ID,
                title = "民法典继承编（节选）",
                kind = SourceKind.TEXT,
                fetchedAt = now,
                contentHash = "demo-hash-1",
                parseStatus = ParseStatus.PARSED,
            ),
            listOf(
                SourceChunk(
                    id = CHUNK_ID,
                    sourceId = SOURCE_ID,
                    projectId = PROJECT_ID,
                    ordinal = 0,
                    text = sourceText,
                ),
            ),
        )

        val inheritance = KnowledgeNode(
            id = "n-inheritance", projectId = PROJECT_ID, title = "继承",
            definition = "继承是自然人死亡后，其财产权利与义务依法转移给他人的法律制度。继承从被继承人死亡时开始。",
            arisesFrom = "一个人死亡后，他的财产关系不能凭空消失，总得有人接手",
            leadsTo = "既然要转移财产，那到底哪些东西算他的'遗产'？",
            prerequisites = emptyList(), related = listOf("遗产范围"), confusions = listOf("继承与遗赠的区别"),
            examples = listOf("张三去世后，其房产由配偶与子女继承。"),
            sourceRefs = listOf(CHUNK_ID), chapterIds = listOf("c1", "c2", "c3"), confirmed = true, depth = 1, order = 0,
        )
        val estate = KnowledgeNode(
            id = "n-estate", projectId = PROJECT_ID, parentId = "n-inheritance", title = "遗产范围",
            definition = "遗产是自然人死亡时遗留的个人合法财产，包括房产、存款、股权等；不含专属于死者的人身权利。",
            arisesFrom = "确定了要继承，就必须先划清'可继承的'到底有哪些",
            leadsTo = "遗产范围清楚了，接下来是谁来继承——由死者说了算，还是法律说了算？",
            prerequisites = listOf("继承"), related = listOf("法定继承"), confusions = listOf("夫妻共同财产中只有一半属于遗产"),
            examples = listOf("李四婚后购房一套，去世时该房产只有属于李四的一半份额计入遗产。"),
            sourceRefs = listOf(CHUNK_ID), chapterIds = listOf("c1"), confirmed = true, depth = 2, order = 0,
        )
        val legalInherit = KnowledgeNode(
            id = "n-legal", projectId = PROJECT_ID, parentId = "n-inheritance", title = "法定继承",
            definition = "没有遗嘱或遗嘱无效时，按法定顺序继承：第一顺序为配偶、子女、父母；第二顺序为兄弟姐妹、祖父母、外祖父母。",
            arisesFrom = "当死者没有留下有效遗嘱，法律必须给一个兜底的分配顺序",
            leadsTo = "但如果死者确实留了话，法律要不要优先尊重他的意愿？",
            prerequisites = listOf("继承"), related = listOf("遗嘱继承"), confusions = listOf("第二顺序继承人在第一顺序存在时不参与继承"),
            examples = listOf("王五未留遗嘱，其配偶与独子作为第一顺序继承人继承全部遗产。"),
            sourceRefs = listOf(CHUNK_ID), chapterIds = listOf("c2", "c3"), confirmed = true, depth = 2, order = 1,
        )
        val willInherit = KnowledgeNode(
            id = "n-will", projectId = PROJECT_ID, parentId = "n-inheritance", title = "遗嘱继承",
            definition = "有合法有效遗嘱时按遗嘱继承。无民事行为能力人、受欺诈胁迫所立、伪造的遗嘱无效。",
            arisesFrom = "法律尊重死者对自己财产的最后安排，于是允许用遗嘱推翻法定顺序",
            leadsTo = "可一旦遗嘱本身有瑕疵，就要判断它是否有效，无效时又回到法定继承",
            prerequisites = listOf("继承"), related = listOf("法定继承"), confusions = listOf("遗嘱无效时回到法定继承"),
            examples = listOf("老人在神志清醒时亲笔书写遗嘱并签名，该遗嘱有效。"),
            sourceRefs = listOf(CHUNK_ID), chapterIds = listOf("c2"), confirmed = true, depth = 2, order = 2,
        )
        container.knowledgeRepository.upsertAll(listOf(inheritance, estate, legalInherit, willInherit))

        val plans = listOf(
            ChapterPlan(
                id = "c1", projectId = PROJECT_ID, order = 1, title = "深夜的律师函",
                plotGoal = "主角收到律师函，得知远房姑母去世并留下遗产，开始接触遗产与继承概念。",
                learningGoals = listOf("为什么人一去世，继承就自动开始了？", "一套房子里，到底哪些部分算'遗产'？"),
                nodeIds = listOf("n-inheritance", "n-estate"),
                wordTarget = 1200, status = ChapterStatus.PUBLISHED, summary = "主角收到律师函，遗产继承故事开场。",
                derivationChain = "人去世，财产不能悬空 → 于是有了继承 → 但要先划清什么算遗产",
                carryOver = "林晓继承了姑母半套房，另一半属于一个她没听说过的人。",
                coveredTitles = listOf("继承", "遗产范围"),
            ),
            ChapterPlan(
                id = "c2", projectId = PROJECT_ID, order = 2, title = "尘封的遗嘱",
                plotGoal = "主角在姑母遗物中发现一封未公证的遗嘱，围绕遗嘱效力展开调查。",
                learningGoals = listOf("死者留了话，为什么能推翻法定的分配顺序？", "什么样的遗嘱会被认定无效？"),
                nodeIds = listOf("n-will", "n-legal"),
                wordTarget = 1200, status = ChapterStatus.PUBLISHED, summary = "发现遗嘱，遗嘱效力成为悬念。",
                derivationChain = "法律尊重死者意愿 → 于是允许遗嘱 → 但遗嘱有瑕疵时要判断效力",
                carryOver = "发现一份三年前的遗嘱，可姑母那时已患阿尔茨海默病，效力存疑。",
                coveredTitles = listOf("遗嘱继承"),
            ),
            ChapterPlan(
                id = "c3", projectId = PROJECT_ID, order = 3, title = "最后的庭审",
                plotGoal = "法庭上两派亲属争夺遗产，最终按法定继承顺序与遗产范围作出判决。",
                learningGoals = listOf("遗嘱无效后，法律靠什么决定谁能继承？", "为什么第一顺序在场时第二顺序拿不到？"),
                nodeIds = listOf("n-legal", "n-estate"),
                wordTarget = 1200, status = ChapterStatus.PUBLISHED, summary = "庭审定音，知识闭环。",
                derivationChain = "遗嘱被判无效 → 回到法定继承 → 按顺序确定谁能拿",
                carryOver = "遗嘱被判无效，房子按法定继承处理，故事收束。",
                coveredTitles = listOf("法定继承", "遗产范围"),
            ),
        )
        // 演示数据也走版本化（第三阶段 3A）：每章一个已发布版本，
        // 章节指针指向它。否则阅读器按 activeVersionId 查不到任何正文
        container.chapterRepository.upsertPlans(plans.map { it.copy(activeVersionId = demoVersionId(it.id)) })
        container.chapterRepository.upsertVersions(
            plans.map { plan ->
                ChapterVersion(
                    id = demoVersionId(plan.id),
                    chapterId = plan.id,
                    projectId = PROJECT_ID,
                    versionNumber = 1,
                    status = ChapterVersionStatus.PUBLISHED,
                    title = plan.title,
                    summary = plan.summary,
                    endingState = plan.carryOver,
                    coveredTitles = plan.coveredTitles,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )

        val blocks = listOf(
            ChapterBlock(id = "c1b1", chapterId = "c1", projectId = PROJECT_ID, order = 0, blockType = BlockType.STORY,
                text = "深夜十一点，林晓收到一封律师函。信上说，素未谋面的远房姑母去世了，留下了一套老城区的小房子。遗产二字像一颗石子，在她平静的生活里荡开涟漪。",
                knowledgeNodeIds = listOf("n-inheritance", "n-estate")),
            ChapterBlock(id = "c1b2", chapterId = "c1", projectId = PROJECT_ID, order = 1, blockType = BlockType.EXPLANATION,
                text = "知识点：继承从被继承人死亡时开始。遗产是自然人死亡时遗留的个人合法财产。夫妻共同财产中，只有属于死者的一半份额计入遗产。",
                knowledgeNodeIds = listOf("n-inheritance", "n-estate"), sourceRefs = listOf(CHUNK_ID)),
            ChapterBlock(id = "c1b3", chapterId = "c1", projectId = PROJECT_ID, order = 2, blockType = BlockType.KEY_POINT,
                text = "一个人去世，他的财产关系不会跟着消失——总得有人接手，这就是继承要解决的第一件事。",
                knowledgeNodeIds = listOf("n-inheritance")),
            ChapterBlock(id = "c1b4", chapterId = "c1", projectId = PROJECT_ID, order = 3, blockType = BlockType.STORY,
                text = "律师告诉她，姑母没有子女，丈夫也早已离世。房子的另一半，也许属于另一个她从未听说的人。",
                knowledgeNodeIds = listOf("n-estate")),
            ChapterBlock(id = "c2b1", chapterId = "c2", projectId = PROJECT_ID, order = 0, blockType = BlockType.STORY,
                text = "整理姑母遗物时，林晓在旧皮箱夹层里发现一封信。信上写着要把房子留给照顾她多年的护工小周，落款日期却在三年前。",
                knowledgeNodeIds = listOf("n-will")),
            ChapterBlock(id = "c2b2", chapterId = "c2", projectId = PROJECT_ID, order = 1, blockType = BlockType.EXPLANATION,
                text = "知识点：有遗嘱时按遗嘱继承。但无民事行为能力人所立、受欺诈胁迫所立、伪造的遗嘱无效。遗嘱无效时，回到法定继承。",
                knowledgeNodeIds = listOf("n-will", "n-legal"), sourceRefs = listOf(CHUNK_ID)),
            ChapterBlock(id = "c2b3", chapterId = "c2", projectId = PROJECT_ID, order = 2, blockType = BlockType.STORY,
                text = "姑母三年前就患上了阿尔茨海默病。这份遗嘱是否有效，成了整个故事的悬念。",
                knowledgeNodeIds = listOf("n-will")),
            ChapterBlock(id = "c3b1", chapterId = "c3", projectId = PROJECT_ID, order = 0, blockType = BlockType.STORY,
                text = "法庭上，护工小周出示了遗嘱，远房侄子则坚称自己是唯一亲人。法官翻开法典，问出了一个关键问题：继承顺序。",
                knowledgeNodeIds = listOf("n-legal")),
            ChapterBlock(id = "c3b2", chapterId = "c3", projectId = PROJECT_ID, order = 1, blockType = BlockType.EXPLANATION,
                text = "知识点：第一顺序继承人是配偶、子女、父母；第二顺序是兄弟姐妹、祖父母、外祖父母。第一顺序存在时，第二顺序不继承。",
                knowledgeNodeIds = listOf("n-legal"), sourceRefs = listOf(CHUNK_ID)),
            ChapterBlock(id = "c3b3", chapterId = "c3", projectId = PROJECT_ID, order = 2, blockType = BlockType.STORY,
                text = "遗嘱因立嘱人无行为能力而无效，房子最终按法定继承处理。林晓和姑母的护工相视一笑——遗产从来不只是财产，更是一段被记住的关系。",
                knowledgeNodeIds = listOf("n-legal", "n-estate")),
        )
        container.chapterRepository.upsertBlocks(blocks.map { it.copy(versionId = demoVersionId(it.chapterId)) })

        container.quizRepository.upsertAll(
            listOf(
                Quiz(id = "q1", projectId = PROJECT_ID, chapterId = "c1", order = 0,
                    question = "继承从什么时候开始？", type = QuizType.CASE,
                    options = listOf("立遗嘱时", "被继承人死亡时", "遗产分割时", "公证时"),
                    answer = "被继承人死亡时", explanation = "民法典规定继承从被继承人死亡时开始。", nodeIds = listOf("n-inheritance")),
                Quiz(id = "q2", projectId = PROJECT_ID, chapterId = "c1", order = 1,
                    question = "夫妻共同财产中的房产，死者去世后哪些部分计入遗产？", type = QuizType.CASE,
                    options = listOf("全部房产", "一半份额", "无份额", "按子女数量划分"),
                    answer = "一半份额", explanation = "遗产是个人合法财产，共同财产中仅死者份额属于遗产。", nodeIds = listOf("n-estate")),
                Quiz(id = "q3", projectId = PROJECT_ID, chapterId = "c2", order = 0,
                    question = "下列哪种遗嘱无效？", type = QuizType.CASE,
                    options = listOf("亲笔书写并签名的遗嘱", "受胁迫所立的遗嘱", "有见证人的打印遗嘱", "神志清醒时立的遗嘱"),
                    answer = "受胁迫所立的遗嘱", explanation = "受欺诈、胁迫所立的遗嘱无效。", nodeIds = listOf("n-will")),
                Quiz(id = "q4", projectId = PROJECT_ID, chapterId = "c3", order = 0,
                    question = "第一顺序继承人存在时，第二顺序继承人能否继承？", type = QuizType.TRUE_FALSE,
                    options = listOf("能", "不能"),
                    answer = "不能", explanation = "继承开始后由第一顺序继承人继承，第二顺序不继承。", nodeIds = listOf("n-legal")),
            ).map { it.copy(versionId = demoVersionId(it.chapterId)) },
        )

        container.progressRepository.save(
            ReadingProgress(id = PROJECT_ID, projectId = PROJECT_ID, chapterId = "c1", charOffset = 0, updatedAt = now),
        )

        container.reviewRepository.upsertAll(
            listOf(
                ReviewItem(id = "r1", projectId = PROJECT_ID, nodeId = "n-estate", dueAt = now, intervalDays = 1, status = ReviewStatus.PENDING),
                ReviewItem(id = "r2", projectId = PROJECT_ID, nodeId = "n-legal", dueAt = now - 86_400_000L, intervalDays = 3, status = ReviewStatus.PENDING),
            ),
        )
    }
}
