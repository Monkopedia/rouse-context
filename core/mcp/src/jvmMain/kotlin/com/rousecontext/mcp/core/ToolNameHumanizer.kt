package com.rousecontext.mcp.core

/**
 * Converts snake_case MCP tool names to human-readable titles for display in
 * notifications and audit UI.
 *
 * Examples:
 *  - `get_steps` -> `Get Steps`
 *  - `set_dnd_state` -> `Set DND State`
 *  - `steps` -> `Steps`
 *  - `dnd` -> `DND`
 *
 * ## Contract
 *
 * Input MUST be snake_case: lowercase ASCII letters and digits separated by
 * single underscores, no leading/trailing underscores, no double underscores,
 * no other characters. Anything else throws [IllegalArgumentException]; a tool
 * name that violates the format is a bug and should surface loudly rather
 * than silently render as garbled UI copy.
 *
 * ## Algorithm
 *
 *  1. Validate the input is snake_case.
 *  2. Split on `_`.
 *  3. For each word: if it appears in [ACRONYMS], uppercase it; otherwise
 *     capitalise the first letter and lowercase the rest.
 *  4. Join with single spaces.
 *
 * The acronym set is an ordinary constant; extend it as new tools ship with
 * abbreviations that should render in uppercase.
 */
object ToolNameHumanizer {

    /** Lowercase acronyms that should render as UPPERCASE in the output. */
    private val ACRONYMS = setOf("dnd")

    private val SNAKE_CASE = Regex("^[a-z0-9]+(_[a-z0-9]+)*$")

    /**
     * Convert [toolName] to a human-readable title.
     *
     * @throws IllegalArgumentException if [toolName] is not valid snake_case.
     */
    fun humanize(toolName: String): String {
        require(toolName.isNotEmpty()) {
            "Tool name must be non-empty snake_case; got empty string"
        }
        require(SNAKE_CASE.matches(toolName)) {
            "Tool name must be snake_case (lowercase letters/digits separated " +
                "by single underscores); got '$toolName'"
        }
        return toolName.split('_').joinToString(separator = " ") { word ->
            if (word in ACRONYMS) {
                word.uppercase()
            } else {
                word.replaceFirstChar { it.uppercaseChar() }
            }
        }
    }
}
