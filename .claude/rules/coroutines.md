---
description: Kotlin coroutine conventions for structured concurrency
globs: "**/*.kt"
---

# Coroutine Conventions

## Structured Concurrency

- NEVER use `GlobalScope`. All coroutines MUST be scoped to a lifecycle owner (ViewModel, LifecycleService, etc.) or a caller-provided scope.
- NEVER create unscoped `CoroutineScope(Dispatchers.IO)` that outlives its usage site. If you need a scope, accept it as a parameter or use `coroutineScope {}`.
- NEVER use `runBlocking` in production code. It is acceptable in tests and `main()` functions only.
- Use `withContext(Dispatchers.IO)` for blocking I/O, not `launch(Dispatchers.IO)` unless you genuinely need fire-and-forget.

## Cancellation

- Respect cancellation. Long-running loops MUST check `isActive` or use cancellable suspension points.
- Use `suspendCancellableCoroutine` instead of `suspendCoroutine` when wrapping callbacks.
- Clean up resources in `finally` blocks or `invokeOnCancellation`.

## Testing

- Use `runBlocking` in tests (not `runTest`) when the code under test uses real I/O or timing.
- Use `CompletableDeferred` for signaling between test coroutines, not `CountDownLatch` or `Thread.sleep`.
- Launch background work as structured children of the test's `runBlocking` scope. Clean up with `coroutineContext.cancelChildren()`.
