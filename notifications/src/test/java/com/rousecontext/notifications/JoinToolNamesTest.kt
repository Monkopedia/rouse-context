package com.rousecontext.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [joinToolNames]. Tests each branch of the natural-language
 * joiner in isolation; no Android dependencies.
 */
class JoinToolNamesTest {

    @Test
    fun `empty list returns empty string`() {
        assertEquals("", joinToolNames(emptyList()))
    }

    @Test
    fun `single name returns the name unchanged`() {
        assertEquals("Get Steps", joinToolNames(listOf("Get Steps")))
    }

    @Test
    fun `two names use 'and' with no comma`() {
        assertEquals(
            "Get Steps and Get Sleep",
            joinToolNames(listOf("Get Steps", "Get Sleep"))
        )
    }

    @Test
    fun `three names use Oxford comma`() {
        assertEquals(
            "Get Steps, Get Sleep, and Get Summary",
            joinToolNames(listOf("Get Steps", "Get Sleep", "Get Summary"))
        )
    }

    @Test
    fun `four names truncate with 'and 1 more'`() {
        assertEquals(
            "Get Steps, Get Sleep, Get Summary, and 1 more",
            joinToolNames(
                listOf("Get Steps", "Get Sleep", "Get Summary", "Get Heart Rate")
            )
        )
    }

    @Test
    fun `ten names truncate with 'and 7 more'`() {
        val names = (1..10).map { "Tool $it" }
        assertEquals(
            "Tool 1, Tool 2, Tool 3, and 7 more",
            joinToolNames(names)
        )
    }

    @Test
    fun `maxUnique one with two names shows 'and 1 more'`() {
        assertEquals(
            "Get Steps, and 1 more",
            joinToolNames(listOf("Get Steps", "Get Sleep"), maxUnique = 1)
        )
    }

    @Test
    fun `maxUnique two with three names shows 'and 1 more'`() {
        assertEquals(
            "Get Steps, Get Sleep, and 1 more",
            joinToolNames(
                listOf("Get Steps", "Get Sleep", "Get Summary"),
                maxUnique = 2
            )
        )
    }

    @Test
    fun `preserves caller-supplied order`() {
        // Caller ranks by call-count desc; this function must not re-sort.
        assertEquals(
            "Zeta, Alpha, and Mu",
            joinToolNames(listOf("Zeta", "Alpha", "Mu"))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxUnique zero throws`() {
        joinToolNames(listOf("Get Steps"), maxUnique = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxUnique negative throws`() {
        joinToolNames(listOf("Get Steps"), maxUnique = -1)
    }
}
