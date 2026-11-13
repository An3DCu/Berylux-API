package com.anadcurukovic.berylux.api.account

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** What a refresh came to: a new token for the player, or a sign-in from scratch. */
sealed interface Refresh {
    data class Rotated(val userId: UUID, val refreshToken: String) : Refresh
    data object Rejected : Refresh
}

/**
 * Refresh tokens: 32 random bytes the phone keeps, stored here only as their SHA-256. Each one
 * lasts [LIFETIME] and works once. A refresh marks it used and gives the next in its chain. A used
 * one that comes back was copied, and every token in its chain stops working.
 */
@Repository
class RefreshTokens(private val jdbc: JdbcClient, private val clock: Clock) {
    private val random = SecureRandom()

    /** The first token of a new chain, at sign-up or log in. */
    fun start(userId: UUID): String = add(userId, familyId = UUID.randomUUID())

    @Transactional
    fun rotate(token: String): Refresh {
        val row = jdbc.sql(
            """
            select id, user_id, family_id, expires_at, revoked_at, used_at
            from refresh_tokens where token_hash = :hash
            for update
            """,
        )
            .param("hash", hash(token))
            .query { rs, _ ->
                Row(
                    id = rs.getObject("id", UUID::class.java),
                    userId = rs.getObject("user_id", UUID::class.java),
                    familyId = rs.getObject("family_id", UUID::class.java),
                    expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
                    revoked = rs.getObject("revoked_at") != null,
                    used = rs.getObject("used_at") != null,
                )
            }
            .optional()
            .orElse(null)
        val now = now()
        return when {
            row == null || row.revoked -> Refresh.Rejected
            row.used -> {
                revokeFamily(row.familyId)
                Refresh.Rejected
            }
            !row.expiresAt.isAfter(now) -> Refresh.Rejected
            else -> {
                jdbc.sql("update refresh_tokens set used_at = :now where id = :id")
                    .param("now", now)
                    .param("id", row.id)
                    .update()
                Refresh.Rotated(row.userId, add(row.userId, row.familyId))
            }
        }
    }

    /** Ends the chain [token] is in, if it's [userId]'s. Anyone else's token is left alone. */
    fun endChain(userId: UUID, token: String) {
        jdbc.sql(
            """
            update refresh_tokens set revoked_at = :now
            where revoked_at is null
              and family_id = (select family_id from refresh_tokens where token_hash = :hash and user_id = :user)
            """,
        )
            .param("now", now())
            .param("hash", hash(token))
            .param("user", userId)
            .update()
    }

    private fun revokeFamily(familyId: UUID) {
        jdbc.sql("update refresh_tokens set revoked_at = :now where family_id = :family and revoked_at is null")
            .param("now", now())
            .param("family", familyId)
            .update()
    }

    private fun add(userId: UUID, familyId: UUID): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = now()
        jdbc.sql(
            """
            insert into refresh_tokens (id, user_id, family_id, token_hash, created_at, expires_at)
            values (:id, :user, :family, :hash, :now, :expires)
            """,
        )
            .param("id", UUID.randomUUID())
            .param("user", userId)
            .param("family", familyId)
            .param("hash", hash(token))
            .param("now", now)
            .param("expires", now.plus(LIFETIME))
            .update()
        return token
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private data class Row(
        val id: UUID,
        val userId: UUID,
        val familyId: UUID,
        val expiresAt: OffsetDateTime,
        val revoked: Boolean,
        val used: Boolean,
    )

    companion object {
        const val TOKEN_BYTES = 32
        val LIFETIME: Duration = Duration.ofDays(30)

        fun hash(token: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    }
}
