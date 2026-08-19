# `McpTool` — tool authoring DSL

Write an MCP tool as a subclass of `McpTool`. The framework generates JSON
Schema, extracts typed arguments, converts results to `CallToolResult`, and
turns unexpected exceptions into proper error responses.

## Minimal template

```kotlin
class GreetTool : McpTool() {
    override val name = "greet"
    override val description = "Say hello"

    val who by stringParam("who", "Person to greet").required()

    override suspend fun execute(): ToolResult =
        ToolResult.Success("hello, $who")
}

// Register — framework introspects params once at registration time.
server.registerTool { GreetTool() }
```

## Param types

| Helper                      | JSON schema          | Kotlin type         |
|-----------------------------|----------------------|---------------------|
| `stringParam(...)`          | `string`             | `String`            |
| `intParam(...)`             | `integer`            | `Int`               |
| `boolParam(...)`            | `boolean`            | `Boolean`           |
| `enumParam(..., X::class)`  | `string` + `enum`    | `X` (Kotlin enum)   |
| `mapParam(...)`             | `object` (str→str)   | `Map<String,String>`|
| `listParam(...)`            | `array<string>`      | `List<String>`      |
| `instantParam(...)`         | `string` date-time   | `java.time.Instant` |

```kotlin
val text    by stringParam("text", "Body text").required()
val count   by intParam("count", "How many").range(1..10).required()
val verbose by boolParam("verbose", "Show details").default(false)
val mode    by enumParam("mode", "DND mode", DndMode::class).required()
val extras  by mapParam("extras", "String extras").optional()
val tags    by listParam("tags", "Tag filter").optional()
val since   by instantParam("since", "Start time").optional()
```

## Nullability

`.required()` reads back as a plain `String` / `Int` / … — `execute()` only runs
once every required param extracted successfully, so there is nothing to unwrap.
`.optional()` and `.default(v)` read back as `T?`.

A param declared with no modifier is required in the schema but still typed `T?`
at the read site; prefer `.required()` so the type tells the truth. Calling
`.required()` after `.optional()`/`.default()` fails fast — it would promise a
non-null value the extractor cannot deliver.

## Modifiers

- `.required()` — read the property as non-null `T`. Required is the schema default.
- `.optional()` — missing value yields `null` instead of an error.
- `.default(v)` — supply a fallback. Implies optional.
- `.choices("a","b")` — string-only; rejects anything outside the set.
- `.range(0..10)` — int-only; rejects values outside the range.

Chain them: `stringParam("mode", "...").default("auto").choices("auto","manual","off")`.
`.required()` goes last — it hands back a delegate, not a `ParamDef`.

## Results

```kotlin
ToolResult.Success("plain text message")
ToolResult.Error("something went wrong")     // isError = true
ToolResult.Json(buildJsonObject { put("ok", JsonPrimitive(true)) })
```

Exceptions thrown by `execute()` are caught and converted to
`ToolResult.Error`. Cancellation is rethrown.

## Migration — before / after

### Before
```kotlin
server.addTool(
    name = "launch_app",
    description = "Launch an installed app by package name",
    inputSchema = ToolSchema(
        properties = buildJsonObject {
            put("package_name", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Package name"))
            })
        },
        required = listOf("package_name")
    )
) { request ->
    val pkg = request.params.arguments?.get("package_name")?.jsonPrimitive?.content
        ?: return@addTool errorResult("Missing package_name")
    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        ?: return@addTool errorResult("App not found: $pkg")
    // ... start intent ...
    successResult("Launched $pkg")
}
```

### After
```kotlin
class LaunchAppTool(private val context: Context) : McpTool() {
    override val name = "launch_app"
    override val description = "Launch an installed app by package name"

    val packageName by stringParam("package_name", "Package name").required()

    override suspend fun execute(): ToolResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolResult.Error("App not found: $packageName")
        // ... start intent ...
        return ToolResult.Success("Launched $packageName")
    }
}

server.registerTool { LaunchAppTool(context) }
```

## Lifecycle

Every call creates a fresh instance via the factory. It is therefore safe
(and expected) to hold request-scoped state as ordinary fields. Do not cache
an instance; the framework owns that decision.
