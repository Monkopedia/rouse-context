package com.rousecontext.app.ui.navigation.destinations

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.AuditDetailContent
import com.rousecontext.app.ui.screens.AuditDetailState
import com.rousecontext.app.ui.screens.AuditDetailUiState
import com.rousecontext.notifications.audit.AuditDao
import com.rousecontext.notifications.capture.FieldEncryptor
import org.koin.compose.koinInject

fun NavGraphBuilder.auditDetailDestination(navController: NavController) {
    composable(
        route = Routes.AUDIT_DETAIL,
        arguments = listOf(
            navArgument("entryId") {
                type = NavType.LongType
            }
        )
    ) { backStackEntry ->
        ConfigureNavBar(
            title = "Audit Detail",
            showBackButton = true,
            onBackPressed = { navController.popBackStack() }
        )
        val entryId = backStackEntry.arguments
            ?.getLong("entryId") ?: return@composable
        val auditDao: AuditDao = koinInject()
        val fieldEncryptor: FieldEncryptor = koinInject()
        var detailState by remember {
            mutableStateOf<AuditDetailUiState>(AuditDetailUiState.Loading)
        }
        LaunchedEffect(entryId) {
            val entry = auditDao.getById(entryId)
            detailState = if (entry != null) {
                AuditDetailUiState.Loaded(
                    AuditDetailState(
                        toolName = entry.toolName,
                        provider = entry.provider,
                        timestampMillis = entry.timestampMillis,
                        durationMs = entry.durationMillis,
                        argumentsJson = fieldEncryptor.decrypt(
                            entry.argumentsJson
                        ) ?: entry.argumentsJson,
                        resultJson = fieldEncryptor.decrypt(
                            entry.resultJson
                        ) ?: entry.resultJson
                    )
                )
            } else {
                AuditDetailUiState.NotFound
            }
        }
        AuditDetailContent(
            uiState = detailState
        )
    }
}
