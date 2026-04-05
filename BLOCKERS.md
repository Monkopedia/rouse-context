# Blockers for T-12: Health Connect MCP Server

## Skipped: HealthConnectIntegration (McpIntegration interface)

The task calls for a `HealthConnectIntegration` class implementing `McpIntegration` from an
`:api` module. That module does not exist in the codebase yet -- there is no
`McpIntegration`, `IntegrationState`, or `IntegrationStateStore` interface to implement.

**What was built instead:** The `HealthConnectMcpServer` directly implements
`McpServerProvider` from `:mcp-core`, which is the existing contract. When the `:api`
module lands, `HealthConnectIntegration` can be added as a thin adapter.

**Skipped test:** `HealthConnectIntegrationTest` -- nothing to test without the interface.

## Skipped: ktlint / detekt verification

The `ktlintCheck`, `ktlintFormat`, and `detekt` Gradle tasks referenced in `CLAUDE.md`
are not configured in the project (no plugin applied in `build.gradle.kts`). Code follows
the style conventions manually but cannot be machine-verified.

## Skipped: Real HealthConnectClient implementation

`HealthConnectRepository` is defined as an interface with a test fake. The production
implementation wrapping `androidx.health.connect.client.HealthConnectClient` requires
an Android `Context` and cannot be unit tested without Robolectric or instrumentation tests.
It should be implemented when the `:app` wiring is built.
