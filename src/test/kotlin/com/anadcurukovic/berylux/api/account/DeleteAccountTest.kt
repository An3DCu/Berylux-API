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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
class DeleteAccountTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
) {
    private val password = "correct horse battery"

    private data class Player(val id: UUID, val email: String, val access: String, val refresh: String)

    private fun post(path: String, json: String): ResultActionsDsl = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = json
        with(TestAddresses.from())
    }

    private fun register(): Player {
        val email = "player-${UUID.randomUUID()}@example.com"
        val json = """
            {"email":"$email","password":"$password","profile":{"name":"Marlowe","sigilSeed":7,"pickSeed":11,
            "timeZone":"Europe/Sarajevo","dayStartHour":4}}
        """.trimIndent()
        val answer = post("/auth/register", json).andExpect { status { isCreated() } }.andReturn()
        val body = answer.response.contentAsString
        val id = jdbc.sql("select id from users where email = :email")
            .param("email", email)
            .query(UUID::class.java)
            .single()
        return Player(id, email, JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"))
    }

    private fun delete(player: Player, json: String = """{"password":"$password"}""", bearer: String? = player.access) =
        mvc.delete("/account") {
            contentType = MediaType.APPLICATION_JSON
            content = json
            bearer?.let { header("Authorization", "Bearer $it") }
        }

    private fun count(sql: String, player: Player) =
        jdbc.sql(sql).param("id", player.id).query(Int::class.java).single()

    private fun rowsOf(player: Player): Map<String, Int> =
        OWNED.associateWith { count("select count(*) from $it where user_id = :id", player) } +
            ("users" to count("select count(*) from users where id = :id", player))

    /** Something in every table that holds a player's things. */
    private fun fillEverything(player: Player) {
        val own = UUID.randomUUID()
        jdbc.sql("insert into custom_challenges (id, user_id, title, updated_at) values (:id, :user, 'Own', now())")
            .param("id", own)
            .param("user", player.id)
            .update()
        jdbc.sql(
            """
            insert into cases (id, user_id, custom_challenge_id, status, is_daily, accepted_at, accepted_on, updated_at)
            values (:id, :user, :own, 'OPEN', false, now(), current_date, now())
            """,
        )
            .param("id", UUID.randomUUID())
            .param("user", player.id)
            .param("own", own)
            .update()
        jdbc.sql("insert into achievement_unlocks (user_id, key, unlocked_at) values (:user, 'first_closed', now())")
            .param("user", player.id)
            .update()
        jdbc.sql("insert into sync_changes (user_id, entity, entity_id, op) values (:user, 'case', 'x', 'UPSERT')")
            .param("user", player.id)
            .update()
    }

    @Test
    fun `deleting an account leaves nothing of theirs`() {
        val player = register()
        fillEverything(player)
        assertEquals(OWNED.size + 1, rowsOf(player).count { it.value > 0 }, rowsOf(player).toString())

        delete(player).andExpect { status { isNoContent() } }

        assertEquals((OWNED + "users").associateWith { 0 }, rowsOf(player))
    }

    @Test
    fun `everyone else's account stays`() {
        val leaving = register()
        val staying = register()
        delete(leaving).andExpect { status { isNoContent() } }
        assertEquals(1, rowsOf(staying).getValue("users"))
        post("/auth/login", """{"email":"${staying.email}","password":"$password"}""").andExpect { status { isOk() } }
    }

    @Test
    fun `a deleted account's tokens stop working at once`() {
        val player = register()
        mvc.get("/cases") { header("Authorization", "Bearer ${player.access}") }.andExpect { status { isNotFound() } }
        delete(player).andExpect { status { isNoContent() } }

        // The access token still has most of its 15 minutes; the account is gone, and so is the token.
        mvc.get("/cases") { header("Authorization", "Bearer ${player.access}") }
            .andExpect { status { isUnauthorized() } }
        delete(player).andExpect { status { isUnauthorized() } }
        post("/auth/refresh", """{"refreshToken":"${player.refresh}"}""").andExpect { status { isUnauthorized() } }
        post("/auth/login", """{"email":"${player.email}","password":"$password"}""")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a wrong password deletes nothing`() {
        val player = register()
        delete(player, """{"password":"not the password"}""").andExpect {
            status { isUnauthorized() }
            jsonPath("$.title") { value("Wrong password") }
        }
        assertEquals(1, rowsOf(player).getValue("users"))
    }

    @Test
    fun `the password is asked for`() {
        val player = register()
        delete(player, "{}").andExpect {
            status { isBadRequest() }
            jsonPath("$.problems.password") { value("missing") }
        }
        assertEquals(1, rowsOf(player).getValue("users"))
    }

    @Test
    fun `deleting needs an access token`() {
        val player = register()
        delete(player, bearer = null).andExpect { status { isUnauthorized() } }
        assertEquals(1, rowsOf(player).getValue("users"))
    }

    @Test
    fun `an access token gets 5 password guesses in 15 minutes`() {
        val player = register()
        repeat(5) { delete(player, """{"password":"guess number $it"}""").andExpect { status { isUnauthorized() } } }
        // The sixth try waits, even with the right password.
        delete(player).andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
        }
        assertEquals(1, rowsOf(player).getValue("users"))
    }

    private companion object {
        val OWNED = listOf(
            "profiles",
            "custom_challenges",
            "cases",
            "refresh_tokens",
            "achievement_unlocks",
            "sync_changes",
        )
    }
}
