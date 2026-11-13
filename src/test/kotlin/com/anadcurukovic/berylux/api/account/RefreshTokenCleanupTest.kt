package com.anadcurukovic.berylux.api.account

import com.anadcurukovic.berylux.api.TestAddresses
import com.anadcurukovic.berylux.api.TestDatabase
import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
class RefreshTokenCleanupTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val cleanup: RefreshTokenCleanup,
) {
    private fun post(path: String, json: String) = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = json
        with(TestAddresses.from())
    }

    private fun user(): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("insert into users (id, email, password_hash) values (:id, :email, 'hash')")
            .param("id", id)
            .param("email", "$id@example.com")
            .update()
        return id
    }

    /** A token row that expires [expires] from now, as a PostgreSQL interval like `-1 second`. */
    private fun token(user: UUID, hash: String, expires: String, used: Boolean = false, ended: Boolean = false) {
        jdbc.sql(
            """
            insert into refresh_tokens (id, user_id, family_id, token_hash, expires_at, used_at, revoked_at)
            values (:id, :user, :family, :hash, now() + cast(:expires as interval), :usedAt, :endedAt)
            """,
        )
            .param("id", UUID.randomUUID())
            .param("user", user)
            .param("family", UUID.randomUUID())
            .param("hash", hash)
            .param("expires", expires)
            .param("usedAt", nowIf(used), Types.TIMESTAMP_WITH_TIMEZONE)
            .param("endedAt", nowIf(ended), Types.TIMESTAMP_WITH_TIMEZONE)
            .update()
    }

    private fun nowIf(happened: Boolean) = if (happened) OffsetDateTime.now() else null

    private fun hashesOf(user: UUID) =
        jdbc.sql("select token_hash from refresh_tokens where user_id = :user order by token_hash")
            .param("user", user)
            .query(String::class.java)
            .list()

    @Test
    fun `only expired refresh tokens are deleted`() {
        val user = user()
        token(user, "expired-$user", "-1 second")
        token(user, "expired-and-used-$user", "-1 day", used = true)
        token(user, "used-$user", "29 days", used = true)
        token(user, "ended-$user", "29 days", ended = true)
        token(user, "live-$user", "30 days")

        cleanup.deleteExpired()

        assertEquals(listOf("ended-$user", "live-$user", "used-$user"), hashesOf(user))
    }

    @Test
    fun `after a cleanup, a copied token that comes back still ends its chain`() {
        val json = """
            {"email":"player-${UUID.randomUUID()}@example.com","password":"correct horse battery",
            "profile":{"name":"Marlowe","sigilSeed":7,"pickSeed":11,"timeZone":"Europe/Sarajevo","dayStartHour":4}}
        """.trimIndent()
        val registered = post("/auth/register", json).andReturn().response.contentAsString
        val first: String = JsonPath.read(registered, "$.refreshToken")
        val refreshed = post("/auth/refresh", """{"refreshToken":"$first"}""").andReturn().response.contentAsString
        val next: String = JsonPath.read(refreshed, "$.refreshToken")

        cleanup.deleteExpired()

        post("/auth/refresh", """{"refreshToken":"$first"}""").andExpect { status { isUnauthorized() } }
        post("/auth/refresh", """{"refreshToken":"$next"}""").andExpect { status { isUnauthorized() } }
    }
}
