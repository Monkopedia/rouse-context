package com.rousecontext.mcp.core

import java.security.SecureRandom

/**
 * Status of a device code poll request.
 */
enum class DeviceCodeStatus {
    AUTHORIZATION_PENDING,
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
data class DeviceCodePollResult(
    val status: DeviceCodeStatus,
    val accessToken: String? = null
)

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
        var approved: Boolean? = null
    )

    private val pendingCodes = mutableListOf<PendingCode>()

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
        }

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

            val elapsed = clock.currentTimeMillis() - pending.createdAt
            if (elapsed > DEVICE_CODE_TTL_MS) {
                pendingCodes.remove(pending)
                return DeviceCodePollResult(DeviceCodeStatus.EXPIRED_TOKEN)
            }

            return when (pending.approved) {
                null -> DeviceCodePollResult(DeviceCodeStatus.AUTHORIZATION_PENDING)
                true -> {
                    pendingCodes.remove(pending)
                    val token = tokenStore.createToken(
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
                    DeviceCodePollResult(DeviceCodeStatus.APPROVED, accessToken = token)
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
        }
    }

    /**
     * Denies the device code identified by the given user code.
     * Called from the app UI when the user taps Deny.
     */
    fun deny(userCode: String) {
        synchronized(this) {
            pendingCodes.find { it.userCode == userCode }?.approved = false
        }
    }

    /**
     * Returns the list of pending device codes for display in the approval UI.
     */
    fun pendingCodes(): List<PendingDeviceCode> {
        synchronized(this) {
            val now = clock.currentTimeMillis()
            return pendingCodes
                .filter { it.approved == null && (now - it.createdAt) <= DEVICE_CODE_TTL_MS }
                .map {
                    PendingDeviceCode(
                        userCode = it.userCode,
                        integrationId = it.integrationId,
                        createdAt = it.createdAt
                    )
                }
        }
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
data class PendingDeviceCode(
    val userCode: String,
    val integrationId: String,
    val createdAt: Long
)
