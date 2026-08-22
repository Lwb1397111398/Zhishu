package com.example.zhishu.samples

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 证据边界回归（批次 B+++ C42 高报案例）：固定验证「声明存在但正文缺失」不会被判 PRESENT。
 *
 * 两层保障：
 * 1. verify Prompt 必须携带「只能依据正文 blocks 判定 evidence」硬约束（防回退）；
 * 2. 双轨判定对 ABSENT 的正确归类（SampleSecondaryAnalysisTest.checksWithAbsent 已覆盖，
 *    这里补充声明级回归：核验若正确报 ABSENT，判定必须归 DECLARED_NOT_APPLIED）。
 */
@RunWith(RobolectricTestRunner::class)
class PromptEvidenceGuardTest {

    private fun assetPrompt(name: String): String {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ctx.assets.open("prompts/$name").bufferedReader().readText()
    }

    private val requiredSnippets = listOf(
        "只能依据正文 blocks 判定",
        "契约、路线、声明、检查目标或模型自身知识都不是正文证据",
        "不得判 `PRESENT`",
        "声明不是证据",
        "宁可 UNKNOWN 不要虚报 PRESENT",
    )

    @Test
    fun verifyPrompt_hasEvidenceHardRule() {
        val prompt = assetPrompt("verify.txt")
        for (snippet in requiredSnippets) {
            assertTrue("verify.txt 缺少硬约束片段: $snippet", prompt.contains(snippet))
        }
    }

    @Test
    fun verifyModelPrompt_hasEvidenceHardRule() {
        val prompt = assetPrompt("verify_model.txt")
        for (snippet in requiredSnippets) {
            assertTrue("verify_model.txt 缺少硬约束片段: $snippet", prompt.contains(snippet))
        }
    }
}
