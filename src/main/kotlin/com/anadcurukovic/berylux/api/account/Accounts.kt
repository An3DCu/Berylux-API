package com.anadcurukovic.berylux.api.account

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Accounts and their profiles in PostgreSQL. */
@Repository
class Accounts(private val jdbc: JdbcClient) {
    /**
     * Stores the account and its profile together, or neither. A taken email throws Spring's
     * DuplicateKeyException from the unique key on `users.email`.
     */
    @Transactional
    fun create(account: NewAccount, passwordHash: String): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("insert into users (id, email, password_hash) values (:id, :email, :hash)")
            .param("id", id)
            .param("email", account.email)
            .param("hash", passwordHash)
            .update()
        jdbc.sql(
            """
            insert into profiles (user_id, name, sigil_seed, pick_seed, time_zone, day_start_hour)
            values (:id, :name, :sigilSeed, :pickSeed, :timeZone, :dayStartHour)
            """,
        )
            .param("id", id)
            .param("name", account.name)
            .param("sigilSeed", account.sigilSeed)
            .param("pickSeed", account.pickSeed)
            .param("timeZone", account.timeZone)
            .param("dayStartHour", account.dayStartHour)
            .update()
        return id
    }

    /** The account's id and password hash, for log in. */
    fun credentials(email: String): Credentials? =
        jdbc.sql("select id, password_hash from users where email = :email")
            .param("email", email)
            .query { rs, _ -> Credentials(rs.getObject("id", UUID::class.java), rs.getString("password_hash")) }
            .optional()
            .orElse(null)
}

data class Credentials(val userId: UUID, val passwordHash: String)
