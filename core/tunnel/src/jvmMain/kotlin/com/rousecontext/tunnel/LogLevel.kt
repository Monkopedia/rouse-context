package com.rousecontext.tunnel

/**
 * Severity level for diagnostic log messages emitted by `:core:tunnel` classes.
 *
 * The call site knows the intended severity; the wiring layer decides how to
 * map each level onto a concrete logging backend (e.g. `android.util.Log`).
 *
 * A separate enum is duplicated in `:core:mcp` to keep the two modules free of
 * cross-dependencies.
 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }
