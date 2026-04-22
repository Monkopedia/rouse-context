package com.rousecontext.notifications

/**
 * Pure text formatter for the session-summary notification body.
 *
 * Joins up to [maxUnique] tool names with natural-language separators and
 * Oxford comma, appending `and N more` when [names] overflows. See #347 /
 * #342 for the locked design.
 *
 * With the default `maxUnique = 3`:
 * - 0 names → empty string (defensive; session summaries never post with
 *   zero entries, but callers may hand this an empty list without needing
 *   to special-case it).
 * - 1 name → the name.
 * - 2 names → `A and B`.
 * - 3 names → `A, B, and C` (Oxford comma).
 * - 4+ names → `A, B, C, and ${N - 3} more`, where N is the *unique* count
 *   passed in, not the total call count.
 *
 * [maxUnique] caps how many names render in full before the `and N more`
 * suffix kicks in. When [maxUnique] is less than the full list, the output
 * always ends in `and K more` where K = names.size - maxUnique. This
 * preserves the `A, and 1 more` shape required for the truncation path even
 * when only 2 names are supplied and [maxUnique] is 1.
 *
 * Input order is preserved. Callers are expected to sort by call-count
 * descending (or whatever ordering they want surfaced first) before
 * passing; this function is format-only.
 *
 * @param names Ordered list of already-humanized tool names.
 * @param maxUnique Maximum number of names to list before truncating to
 *   `and N more`. Must be >= 1. Defaults to 3 per the locked design.
 * @return Formatted string suitable for use as a notification body.
 */
internal fun joinToolNames(names: List<String>, maxUnique: Int = 3): String {
    require(maxUnique >= 1) { "maxUnique must be >= 1, got $maxUnique" }
    val total = names.size
    return when {
        total == 0 -> ""
        total == 1 -> names[0]
        // Truncation path: show `maxUnique` names then `and K more`.
        total > maxUnique -> {
            val shown = names.take(maxUnique)
            val remaining = total - maxUnique
            shown.joinToString(", ") + ", and $remaining more"
        }
        // Non-truncated 2-name join: "A and B" (no comma, no Oxford comma).
        total == 2 -> "${names[0]} and ${names[1]}"
        // Non-truncated 3+ name join with Oxford comma: "A, B, and C".
        else -> {
            val head = names.dropLast(1).joinToString(", ")
            "$head, and ${names.last()}"
        }
    }
}
