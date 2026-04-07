package com.rousecontext.app.debug

import com.rousecontext.api.McpIntegration
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module for debug-only dependencies.
 *
 * Provides [TestMcpIntegration] as an available integration that can be
 * set up through the normal integration picker, just like any other.
 */
val debugModule = module {
    single<McpIntegration>(named("test")) { TestMcpIntegration() }
}
