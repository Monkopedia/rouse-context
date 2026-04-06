package com.rousecontext.app.debug

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Koin module for debug-only dependencies.
 *
 * Provides [TestMcpIntegration] and auto-enables it so the test tools are
 * immediately available without manual setup.
 */
val debugModule = module {
    single<McpIntegration>(named("test")) {
        val integration = TestMcpIntegration()
        val stateStore = get<IntegrationStateStore>()
        stateStore.setUserEnabled(integration.id, true)
        integration
    }
}
