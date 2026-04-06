# Usage Integration

## Overview
Exposes device usage statistics to AI clients via MCP. Answers questions like "how much time did I spend on Twitter today?" or "what are my most-used apps this week?"

## Android Requirements
- `PACKAGE_USAGE_STATS` permission — requires explicit user grant in Settings > Apps > Special access > Usage access
- API: `UsageStatsManager`
- Available since API 21 (Lollipop)

## MCP Tools

### `get_usage_summary`
Overall usage summary for a time period.
- **Params**: `period` ("today", "yesterday", "week", "month"), `limit` (max apps to return, default 10)
- **Returns**: `{period_start, period_end, total_screen_time_minutes, apps: [{package, name, foreground_minutes, last_used}]}`
- Sorted by foreground time descending

### `get_app_usage`
Detailed usage for a specific app.
- **Params**: `package_name`, `period` ("today", "yesterday", "week", "month")
- **Returns**: `{package, name, foreground_minutes, times_opened, first_used, last_used, daily_breakdown: [{date, minutes}]}`

### `get_usage_events`
Raw usage events for detailed analysis.
- **Params**: `since` (ISO datetime), `until` (ISO datetime), `package` (optional filter), `limit` (default 100)
- **Returns**: Array of `{package, event_type, time}` where event_type is "foreground", "background", "user_interaction", etc.

### `compare_usage`
Compare usage across periods.
- **Params**: `period1` (e.g. "this_week"), `period2` (e.g. "last_week"), `limit` (default 5)
- **Returns**: `{period1_total_minutes, period2_total_minutes, change_percent, biggest_increases: [{package, name, change_minutes}], biggest_decreases: [...]}`

## Setup Flow
1. Integration card: "Usage Stats — See how you use your device"
2. Tap → opens system Usage Access settings page
3. On return, verify permission granted
4. No additional configuration needed

## Architecture
- New `:usage` module
- `UsageMcpProvider` implements `McpServerProvider`
- Wraps `UsageStatsManager` queries
- Resolves package names to human-readable app names via `PackageManager`
- All queries are read-only — no state modification

## Notes
- `UsageStatsManager.queryUsageStats()` returns per-app aggregated stats
- `UsageStatsManager.queryEvents()` returns individual foreground/background events
- Data is available for the last few days to weeks depending on device and Android version
- Some OEMs restrict usage stats availability
