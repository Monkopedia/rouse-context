package com.rousecontext.mcp.core

/**
 * Version string the module's tests advertise in the MCP handshake.
 *
 * [configureMcpRouting] and [McpSession] deliberately have no default for
 * `serverVersion`: the only correct value lives in the app's `BuildConfig`,
 * which this KMP module cannot see, so a default here can only ever be wrong.
 * Before #603 the default was `"0.1.0"` and every shipped release advertised
 * it. Tests pass this obviously-fake value so a real version can never be
 * mistaken for one that came from a build.
 */
internal const val TEST_SERVER_VERSION = "0.0.0-test"
