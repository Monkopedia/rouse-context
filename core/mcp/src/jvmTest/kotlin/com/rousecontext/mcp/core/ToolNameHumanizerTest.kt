package com.rousecontext.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [ToolNameHumanizer].
 *
 * Exercises the snake_case -> Title Case (with acronym set) algorithm and
 * the fail-loud validation on any input that is not valid snake_case.
 */
class ToolNameHumanizerTest {

    @Test
    fun `humanize converts simple snake_case to Title Case`() {
        assertEquals("Get Steps", ToolNameHumanizer.humanize("get_steps"))
        assertEquals("Get Sleep", ToolNameHumanizer.humanize("get_sleep"))
        assertEquals("Get Summary", ToolNameHumanizer.humanize("get_summary"))
    }

    @Test
    fun `humanize uppercases known acronyms`() {
        assertEquals("Set DND State", ToolNameHumanizer.humanize("set_dnd_state"))
        assertEquals("Get DND State", ToolNameHumanizer.humanize("get_dnd_state"))
    }

    @Test
    fun `humanize capitalises a single word`() {
        assertEquals("Steps", ToolNameHumanizer.humanize("steps"))
    }

    @Test
    fun `humanize uppercases a standalone acronym`() {
        assertEquals("DND", ToolNameHumanizer.humanize("dnd"))
    }

    @Test
    fun `humanize throws on empty input`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolNameHumanizer.humanize("")
        }
    }

    @Test
    fun `humanize throws on camelCase input`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolNameHumanizer.humanize("GetSteps")
        }
    }

    @Test
    fun `humanize throws on mixed uppercase in underscored input`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolNameHumanizer.humanize("Get_Steps")
        }
    }

    @Test
    fun `humanize throws on kebab-case input`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolNameHumanizer.humanize("get-steps")
        }
    }

    @Test
    fun `humanize throws on double underscores`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolNameHumanizer.humanize("get__steps")
        }
    }

    @Test
    fun `humanize throws on leading underscore`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolNameHumanizer.humanize("_get_steps")
        }
    }

    @Test
    fun `humanize throws on trailing underscore`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolNameHumanizer.humanize("get_steps_")
        }
    }

    @Test
    fun `humanize throws on punctuation`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolNameHumanizer.humanize("get.steps")
        }
    }

    @Test
    fun `humanize throws on whitespace`() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolNameHumanizer.humanize("get steps")
        }
    }
}
