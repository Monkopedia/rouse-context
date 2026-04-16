package com.rousecontext.mcp.tool

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification

/**
 * Minimal [ClientConnection] stand-in used by DSL framework tests. We only need
 * the handler signature satisfied — the registered tool handlers do not touch
 * the connection for simple request/response tool calls.
 */
internal object StubClientConnection : ClientConnection {
    override val sessionId: String = "stub"

    override suspend fun notification(
        notification: ServerNotification,
        relatedRequestId: RequestId?
    ) = Unit
    override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult =
        error("stub")
    override suspend fun createMessage(
        request: CreateMessageRequest,
        options: RequestOptions?
    ): CreateMessageResult = error("stub")
    override suspend fun listRoots(
        request: ListRootsRequest,
        options: RequestOptions?
    ): ListRootsResult = error("stub")
    override suspend fun createElicitation(
        message: String,
        requestedSchema: ElicitRequestParams.RequestedSchema,
        options: RequestOptions?
    ): ElicitResult = error("stub")
    override suspend fun createElicitation(
        request: ElicitRequest,
        options: RequestOptions?
    ): ElicitResult = error("stub")
    override suspend fun createElicitation(
        message: String,
        elicitationId: String,
        url: String,
        options: RequestOptions?
    ): ElicitResult = error("stub")
    override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) = Unit
    override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) = Unit
    override suspend fun sendResourceListChanged() = Unit
    override suspend fun sendToolListChanged() = Unit
    override suspend fun sendPromptListChanged() = Unit
    override suspend fun sendElicitationComplete(notification: ElicitationCompleteNotification) =
        Unit
}
