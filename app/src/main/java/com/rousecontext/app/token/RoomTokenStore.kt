package com.rousecontext.app.token

import com.rousecontext.mcp.core.ACCESS_TOKEN_EXPIRES_IN_SECONDS
import com.rousecontext.mcp.core.ACCESS_TOKEN_TTL_MS
import com.rousecontext.mcp.core.REFRESH_TOKEN_TTL_MS
import com.rousecontext.mcp.core.TokenInfo
import com.rousecontext.mcp.core.TokenPair
import com.rousecontext.mcp.core.TokenStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [TokenStore] implementation backed by Room.
 *
 * Tokens are generated as 32 random bytes encoded as base64url.
 * Only the SHA-256 hash of the token is persisted.
 */
class RoomTokenStore(private val dao: TokenDao, private val transactions: TokenTransactionRunner) :
    TokenStore {

    override fun validateToken(integrationId: String, token: String): Boolean {
        val hash = hashToken(token)
        val entity = dao.findByHash(integrationId, hash) ?: return false
        val now = System.currentTimeMillis()
        if (now > entity.expiresAt) return false
        dao.updateLastUsed(entity.id, now)
        return true
    }

    override fun resolveClientName(integrationId: String, token: String): String? {
        val hash = hashToken(token)
        val entity = dao.findByHash(integrationId, hash) ?: return null
        // label is clientName fallback to clientId at insert time; return
        // null when it would equal clientId so callers get a "not set" signal
        // rather than showing a raw UUID/id as a friendly name.
        return entity.label.takeIf { it != entity.clientId }
    }

    override fun resolveClientId(integrationId: String, token: String): String? {
        val hash = hashToken(token)
        val entity = dao.findByHash(integrationId, hash) ?: return null
        return entity.clientId
    }

    override fun resolveClientLabel(integrationId: String, token: String): String? {
        val hash = hashToken(token)
        val entity = dao.findByHash(integrationId, hash) ?: return null
        // TokenEntity.label is set to `clientName ?: clientId` at insert time,
        // so this always returns a stable non-empty identifier for valid
        // tokens (see issue #344). Audit rows need this to attribute every
        // tool call to a client even when DCR did not supply a client_name.
        return entity.label
    }

    override fun createTokenPair(
        integrationId: String,
        clientId: String,
        clientName: String?
    ): TokenPair = createTokenPair(
        integrationId = integrationId,
        clientId = clientId,
        clientName = clientName,
        familyId = UUID.randomUUID().toString()
    )

    private fun createTokenPair(
        integrationId: String,
        clientId: String,
        clientName: String?,
        familyId: String
    ): TokenPair {
        val accessRaw = generateRawToken()
        val accessToken = Base64.getUrlEncoder().withoutPadding().encodeToString(accessRaw)
        val refreshRaw = generateRawToken()
        val refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(refreshRaw)

        val now = System.currentTimeMillis()
        dao.insert(
            TokenEntity(
                integrationId = integrationId,
                clientId = clientId,
                tokenHash = hashToken(accessToken),
                refreshTokenHash = hashToken(refreshToken),
                label = clientName ?: clientId,
                familyId = familyId,
                createdAt = now,
                lastUsedAt = now,
                expiresAt = now + ACCESS_TOKEN_TTL_MS,
                refreshExpiresAt = now + REFRESH_TOKEN_TTL_MS
            )
        )

        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = ACCESS_TOKEN_EXPIRES_IN_SECONDS
        )
    }

    override fun revokeToken(integrationId: String, token: String) {
        dao.deleteByHash(integrationId, hashToken(token))
    }

    override fun revokeByClientId(integrationId: String, clientId: String) {
        dao.deleteByClientId(integrationId, clientId)
    }

    override fun upgradeClientLabel(integrationId: String, clientId: String, newLabel: String) {
        dao.updateLabelByClientId(integrationId, clientId, newLabel)
    }

    /**
     * Redeems [refreshToken] for a fresh pair, rotating the parent row.
     *
     * Exactly one redemption of a given refresh token can succeed. A second
     * one -- whether it is a replay minutes later or a concurrent request
     * racing the first -- is a reuse event under OAuth 2.1 §4.14 and revokes
     * the whole family.
     *
     * That is strict by design and it has a cost: a well-behaved client that
     * fires two refreshes at once (a 401 storm does this) now has its family
     * revoked and its user sent back through authorization, where before both
     * refreshes quietly succeeded. The spec-clean softening is a short grace
     * window in which the immediately-preceding token returns the child that
     * was already minted for it instead of counting as reuse; that is a
     * product decision and is deliberately NOT taken here.
     */
    override fun refreshToken(integrationId: String, refreshToken: String): TokenPair? {
        val hash = hashToken(refreshToken)
        val entity = dao.findByRefreshHash(integrationId, hash) ?: return null

        // Expiry is only a reason to refuse a token that has NOT been redeemed.
        // A token that has already been rotated and is presented again is a
        // reuse event whatever the clock says, and the compare-and-swap below
        // is what detects it -- so the expiry check is guarded rather than
        // allowed to return early ahead of it.
        if (entity.rotatedAt == null && System.currentTimeMillis() > entity.refreshExpiresAt) {
            return null
        }

        // ONE transaction covering all three parts of a rotation: consume the
        // parent, then either mint the child or revoke the family. It is not
        // enough for the compare-and-swap alone to be atomic (issue #760): a
        // CAS loser revoking the family between a CAS winner's swap and its
        // child insert left the winner inserting a live token into a family
        // the store had already revoked, because at delete time the child did
        // not exist yet.
        //
        //   A: CAS -> 1  |  B: CAS -> 0  |  B: deleteByFamilyId  |  A: INSERT
        //
        // "At most one descendant" survived that; "a revoked family stays
        // revoked" did not. SQLite serializes write transactions, so with the
        // insert and the delete inside one, a revocation can only be ordered
        // wholly before or wholly after a rotation, and either order ends with
        // the family empty.
        //
        // The read above is deliberately left outside: it cannot widen the
        // race. The CAS is keyed on `id` with `rotatedAt IS NULL`, so a stale
        // read can only ever lose the CAS -- including when the row has since
        // been deleted by a revocation -- and losing routes to the reuse
        // branch, which is the correct answer for a stale token.
        return transactions.inTransaction {
            val now = System.currentTimeMillis()
            // Rotate: keep the parent row (with rotatedAt set) so future
            // replays are detectable; mint a child pair with the same
            // familyId. Setting rotatedAt also invalidates the old access
            // token via findByHash.
            if (dao.markRotatedIfUnrotated(entity.id, now) == 0) {
                // Reuse detection (OAuth 2.1 §4.14): revoke the entire family.
                dao.deleteByFamilyId(entity.familyId)
                return@inTransaction null
            }
            createTokenPair(
                integrationId = integrationId,
                clientId = entity.clientId,
                clientName = entity.label,
                familyId = entity.familyId
            )
        }
    }

    override fun listTokens(integrationId: String): List<TokenInfo> =
        dao.listByIntegration(integrationId).map { entity -> entity.toTokenInfo() }

    override fun tokensFlow(integrationId: String): Flow<List<TokenInfo>> =
        dao.observeByIntegration(integrationId).map { list ->
            list.map { entity -> entity.toTokenInfo() }
        }

    override fun hasTokens(integrationId: String): Boolean =
        dao.countByIntegration(integrationId) > 0

    private fun TokenEntity.toTokenInfo(): TokenInfo = TokenInfo(
        integrationId = integrationId,
        clientId = clientId,
        createdAt = createdAt,
        lastUsedAt = lastUsedAt,
        label = label
    )

    private fun generateRawToken(): ByteArray {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    companion object {
        private const val TOKEN_BYTES = 32

        fun hashToken(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest.digest(token.toByteArray(Charsets.UTF_8)))
        }
    }
}
