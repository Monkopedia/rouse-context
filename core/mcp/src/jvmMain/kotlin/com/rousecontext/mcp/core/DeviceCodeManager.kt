package com.rousecontext.mcp.core

import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Status of a device code poll request.
 */
enum class DeviceCodeStatus {
    AUTHORIZATION_PENDING,
    SLOW_DOWN,
    APPROVED,
    ACCESS_DENIED,
    EXPIRED_TOKEN,
    INVALID_CODE
}

/**
 * Response from [DeviceCodeManager.authorize].
 */
data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val interval: Int = DEFAULT_POLL_INTERVAL_SECONDS
)

/**
 * Result of [DeviceCodeManager.poll].
 */
data class DeviceCodePollResult(val status: DeviceCodeStatus, val tokenPair: TokenPair? = null)

private const val DEFAULT_POLL_INTERVAL_SECONDS = 5
private const val DEVICE_CODE_TTL_MS = 10L * 60 * 1000 // 10 minutes

/**
 * Characters allowed in user codes. Excludes 0, O, 1, I, L to avoid ambiguity.
 */
private const val USER_CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
private const val USER_CODE_HALF_LENGTH = 6

/**
 * Manages OAuth 2.1 device authorization grant (RFC 8628) for per-integration auth.
 *
 * Each integration gets independent device codes and tokens. A device code expires
 * after 10 minutes. The user approves or denies via the app UI using the user_code.
 */
class DeviceCodeManager(
    private val tokenStore: TokenStore = InMemoryTokenStore(),
    private val clock: Clock = SystemClock,
    private val auditListener: AuditListener? = null
) {

    private data class PendingCode(
        val deviceCode: String,
        val userCode: String,
        val integrationId: String,
        val createdAt: Long,
        // null = pending, true = approved, false = denied
        var approved: Boolean? = null,
        var lastPolledAt: Long? = null
    )

    private val pendingCodes = mutableListOf<PendingCode>()

    /**
     * Called when a new device authorization request is created.
     * Parameters are the user code and the integration the request was made
     * against -- the one passed to [authorize], never a session-wide default.
     * Set this from the app layer to trigger notifications.
     */
    var onNewRequest: ((userCode: String, integration: String) -> Unit)? = null

    private val _pendingCodesFlow = MutableStateFlow<List<PendingDeviceCode>>(emptyList())

    /**
     * Observable list of device codes awaiting approval. Mirrors
     * [AuthorizationCodeManager.pendingRequestsFlow] so the approval UI can
     * render both flows from a single list without polling.
     */
    val pendingCodesFlow: StateFlow<List<PendingDeviceCode>> = _pendingCodesFlow.asStateFlow()

    /**
     * Creates a new device code + user code pair for the given integration.
     * Returns the codes and polling interval.
     */
    fun authorize(integrationId: String): DeviceCodeResponse {
        val deviceCode = generateDeviceCode()
        val userCode = generateUserCode()
        val now = clock.currentTimeMillis()

        synchronized(this) {
            cleanup(now)
            pendingCodes.add(
                PendingCode(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    integrationId = integrationId,
                    createdAt = now
                )
            )
            publishPendingCodesLocked(now)
        }

        // Outside the lock, mirroring AuthorizationCodeManager.createRequest: the
        // app-layer listener posts a notification and must not run under it.
        // `integrationId` is the one the request resolved to, never a default.
        onNewRequest?.invoke(userCode, integrationId)

        return DeviceCodeResponse(
            deviceCode = deviceCode,
            userCode = userCode
        )
    }

    /**
     * Polls the status of a device code. Called by the MCP client.
     */
    fun poll(deviceCode: String): DeviceCodePollResult {
        synchronized(this) {
            val now = clock.currentTimeMillis()
            val pending = pendingCodes.find { it.deviceCode == deviceCode }
                ?: return DeviceCodePollResult(DeviceCodeStatus.INVALID_CODE)

            val elapsed = now - pending.createdAt
            if (elapsed > DEVICE_CODE_TTL_MS) {
                pendingCodes.remove(pending)
                publishPendingCodesLocked(now)
                return DeviceCodePollResult(DeviceCodeStatus.EXPIRED_TOKEN)
            }

            // RFC 8628 §3.5: slow_down if client polls faster than the issued interval
            val lastPoll = pending.lastPolledAt
            pending.lastPolledAt = now
            if (lastPoll != null && (now - lastPoll) < DEFAULT_POLL_INTERVAL_SECONDS * 1000L) {
                return DeviceCodePollResult(DeviceCodeStatus.SLOW_DOWN)
            }

            return when (pending.approved) {
                null -> DeviceCodePollResult(DeviceCodeStatus.AUTHORIZATION_PENDING)
                true -> {
                    pendingCodes.remove(pending)
                    val pair = tokenStore.createTokenPair(
                        pending.integrationId,
                        "device-code-client"
                    )
                    auditListener?.onTokenGranted(
                        TokenGrantEvent(
                            timestamp = clock.currentTimeMillis(),
                            integration = pending.integrationId,
                            clientId = "device-code-client",
                            clientName = null,
                            grantType = "device_code"
                        )
                    )
                    DeviceCodePollResult(DeviceCodeStatus.APPROVED, tokenPair = pair)
                }
                false -> {
                    pendingCodes.remove(pending)
                    DeviceCodePollResult(DeviceCodeStatus.ACCESS_DENIED)
                }
            }
        }
    }

    /**
     * Approves the device code identified by the given user code.
     * Called from the app UI when the user taps Approve.
     */
    fun approve(userCode: String) {
        synchronized(this) {
            pendingCodes.find { it.userCode == userCode }?.approved = true
            publishPendingCodesLocked(clock.currentTimeMillis())
        }
    }

    /**
     * Denies the device code identified by the given user code.
     * Called from the app UI when the user taps Deny.
     */
    fun deny(userCode: String) {
        synchronized(this) {
            pendingCodes.find { it.userCode == userCode }?.approved = false
            publishPendingCodesLocked(clock.currentTimeMillis())
        }
    }

    /**
     * Returns the list of pending device codes for display in the approval UI.
     */
    fun pendingCodes(): List<PendingDeviceCode> {
        synchronized(this) {
            return snapshotLocked(clock.currentTimeMillis())
        }
    }

    /**
     * Republishes [pendingCodesFlow]. Must be called inside synchronized(this).
     */
    private fun publishPendingCodesLocked(now: Long) {
        _pendingCodesFlow.value = snapshotLocked(now)
    }

    /**
     * Unresolved, unexpired codes as of [now]. Must be called inside synchronized(this).
     */
    private fun snapshotLocked(now: Long): List<PendingDeviceCode> = pendingCodes
        .filter { it.approved == null && (now - it.createdAt) <= DEVICE_CODE_TTL_MS }
        .map {
            PendingDeviceCode(
                userCode = it.userCode,
                integrationId = it.integrationId,
                createdAt = it.createdAt
            )
        }

    /**
     * Removes expired pending codes. Must be called inside synchronized(this).
     */
    private fun cleanup(now: Long) {
        pendingCodes.removeAll { (now - it.createdAt) > DEVICE_CODE_TTL_MS }
    }

    private fun generateDeviceCode(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.encodeBase64Url()
    }

    private fun generateUserCode(): String {
        val random = SecureRandom()
        val first = (1..USER_CODE_HALF_LENGTH)
            .map { USER_CODE_CHARS[random.nextInt(USER_CODE_CHARS.length)] }
            .toCharArray()
            .concatToString()
        val second = (1..USER_CODE_HALF_LENGTH)
            .map { USER_CODE_CHARS[random.nextInt(USER_CODE_CHARS.length)] }
            .toCharArray()
            .concatToString()
        return "$first-$second"
    }
}

/**
 * A pending device code awaiting user approval, for display in the UI.
 */
data class PendingDeviceCode(val userCode: String, val integrationId: String, val createdAt: Long)
