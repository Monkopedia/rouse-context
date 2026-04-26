# Agent Quickstart

A short reference for agents (and humans) writing code in this repo. For the
full architecture and module layout, see `docs/workflow.md` and the
project-root `CLAUDE.md`.

## JSON construction

Tools that return structured data MUST use `@Serializable` data classes plus
`Json.encodeToString` (or `Json.encodeToJsonElement` if a tree is preferable).
Manual template literals — `"""{"field":"$value"}"""` — are NOT permitted in
production code.

### Why

Issue [#417](https://github.com/Monkopedia/rouse-context/issues/417) shipped
malformed JSON to user testing because a template-literal error helper
interpolated a raw exception message containing quotes, producing output the
connector proxy refused to parse. The `@Serializable` + `encodeToString` path
escapes embedded quotes/backslashes/control characters automatically; the
manual path does not.

### Enforcement

Two gates run in CI:

1. **Test harness** (`McpToolTestHarness` in
   `integrations/src/test/kotlin/com/rousecontext/integrations/testing/`) —
   parse-asserts every `CallToolResult` body returned through the harness.
   Any tool whose result body fails `Json.parseToJsonElement` causes the
   test to fail with the offending tool name and body content.
2. **Static check** (`scripts/check-no-manual-json.sh`) — runs in the
   `Android CI` workflow. Greps for `"""{` in `*/src/main/*.kt` and fails the
   build if any match isn't allowlisted.

### The `// allow-manual-json` marker

For the rare case where a template literal is genuinely safe (e.g. a
`@Preview`-only Compose fixture with hard-coded literals that never see
runtime input), append a marker comment to the line:

```kotlin
val sample = """{"steps":7000}""" // allow-manual-json: @Preview fixture, no interpolation
```

The reason text after the colon should describe **why this isn't a runtime
risk** — typically because no user-controlled value flows into the template.
A reviewer should treat the absence of a clear reason as a blocker.

`AuditDetailScreen.kt` is implicitly allowlisted in the script because its
preview fixtures exist purely for IDE rendering and never reach runtime; new
files should use the inline marker rather than expanding the file allowlist.

## Test harness

When writing or refactoring an `*McpProvider*Test`, use
`McpToolTestHarness` rather than hand-rolling the mockk capture pattern:

```kotlin
private val harness = McpToolTestHarness()
private val fakeConnection: ClientConnection = mockk(relaxed = true)

@Before fun setUp() {
    val server = harness.createMockServer()
    MyMcpProvider(...).register(server)
}

@Test fun `tool does the thing`() = runBlocking {
    val result = harness.callTool(
        name = "my_tool",
        arguments = buildJsonObject { put("x", JsonPrimitive(1)) },
        connection = fakeConnection
    )
    // Body is guaranteed JSON-parseable; assert further as needed.
}
```

Pass `expectJsonBody = false` only for tools whose contract specifies a
plain-text body (rare). Document the reason inline if you do.
