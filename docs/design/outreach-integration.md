# Outreach Integration

## Overview
Allows AI clients to push actions to the device: launch apps, open links, copy to clipboard, send notifications, and optionally control Do Not Disturb state.

## MCP Tools

### `launch_app`
Opens an app on the device.
- **Params**: `package_name` (e.g. "com.spotify.music"), `action` (optional intent action), `extras` (optional key-value map)
- **Returns**: `{success, message}`
- Uses `packageManager.getLaunchIntentForPackage()`

### `open_link`
Opens a URL in the default browser or appropriate app.
- **Params**: `url`
- **Returns**: `{success, message}`
- Uses `Intent(ACTION_VIEW, Uri.parse(url))`

### `copy_to_clipboard`
Copies text to the device clipboard.
- **Params**: `text`, `label` (optional, describes the content)
- **Returns**: `{success}`
- Uses `ClipboardManager`

### `send_notification`
Sends a notification to the user on the device.
- **Params**: `title`, `message`, `delay_seconds` (optional, default 0), `channel` (optional, default "outreach"), `actions` (optional array of `{label, url}`)
- **Returns**: `{success, notification_id}`
- For delayed: uses `AlarmManager` or `WorkManager` one-time task
- Notification actions open URLs via `open_link`

### `list_installed_apps`
Lists installed apps for use with `launch_app`.
- **Params**: `filter` (optional text search on app name)
- **Returns**: Array of `{package, name, icon_available}`

### `get_dnd_state` (optional, requires separate permission)
Checks Do Not Disturb status.
- **Params**: none
- **Returns**: `{enabled, mode, until}` where mode is "total_silence", "priority_only", "alarms_only"
- Requires `ACCESS_NOTIFICATION_POLICY` permission

### `set_dnd_state` (optional, requires separate permission)
Controls Do Not Disturb.
- **Params**: `enabled`, `mode` (optional), `duration_minutes` (optional)
- **Returns**: `{success, previous_state}`
- Requires `ACCESS_NOTIFICATION_POLICY` permission

## Permissions
- Basic tools (launch, link, clipboard, notification): no special permissions beyond normal app permissions
- DND tools: `ACCESS_NOTIFICATION_POLICY` — requires user to grant in Settings > Apps > Special access > Do Not Disturb access
- DND is **opt-in during setup**: user chooses whether to enable DND control separately from the basic outreach tools

## Setup Flow
1. Integration card: "Outreach — Let AI take actions on your device"
2. Enable → basic tools available immediately
3. Optional toggle: "Allow Do Not Disturb control" → opens system DND access settings
4. On return, verify permission granted, enable DND tools

## Architecture
- Lives in a new `:outreach` module (or within `:api` as a provider)
- `OutreachMcpProvider` implements `McpServerProvider`
- DND tools conditionally registered based on permission state
- Delayed notifications via `WorkManager` one-time work
- Clipboard operations need to run on main thread (use `withContext(Dispatchers.Main)`)

## Security Considerations
- `launch_app` should NOT allow launching with arbitrary intent extras that could exploit other apps
- `open_link` should validate URL scheme (http/https only, no file:// or content://)
- `send_notification` rate limit to prevent spam
- All actions logged to audit
