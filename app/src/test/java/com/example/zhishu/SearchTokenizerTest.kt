package com.example.zhishu

import com.example.zhishu.search.SearchTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FTS 分词规则（P2） */
class SearchTokenizerTest {

    @Test
    fun indexBody_producesBigrams() {
        assertEquals("遗产 产继 继承", SearchTokenizer.indexBody("遗产继承"))
    }

    @Test
    fun indexBody_dropsWhitespaceAndPunctuation() {
        assertEquals("遗产 产继 继承", SearchTokenizer.indexBody("遗产，继承。"))
        assertEquals("遗产 产继 继承", SearchTokenizer.indexBody(" 遗产 继承 "))
    }

    @Test
    fun matchQuery_multiCharUsesPhrase() {
        assertEquals("\"继承\"", SearchTokenizer.matchQuery("继承"))
        assertEquals("\"遗产 产继 继承\"", SearchTokenizer.matchQuery("遗产继承"))
    }

    @Test
    fun matchQuery_singleCharUsesPrefix() {
        assertEquals("承*", SearchTokenizer.matchQuery("承"))
    }

    @Test
    fun matchQuery_blankIsNull() {
        assertNull(SearchTokenizer.matchQuery("   "))
        assertNull(SearchTokenizer.matchQuery("，。！"))
    }

    /** 查询的二元组必须是原文二元组的子序列，才能靠短语匹配命中子串 */
    @Test
    fun queryBigrams_areSubsequenceOfIndexedBody() {
        val body = SearchTokenizer.indexBody("老人立下遗嘱后子女为遗产继承争执")
        val match = SearchTokenizer.matchQuery("遗产继承")!!.trim('"')
        assertTrue(body.contains(match))
    }

    @Test
    fun caseInsensitiveForLatin() {
        assertEquals(SearchTokenizer.indexBody("Abc"), SearchTokenizer.indexBody("aBC"))
        assertEquals(SearchTokenizer.matchQuery("ABC"), SearchTokenizer.matchQuery("abc"))
    }
}
