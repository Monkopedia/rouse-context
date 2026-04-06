package com.rousecontext.app.debug

import org.koin.core.module.Module

/**
 * Returns Koin modules that should only be loaded in debug builds.
 */
fun debugModules(): List<Module> = listOf(debugModule)
