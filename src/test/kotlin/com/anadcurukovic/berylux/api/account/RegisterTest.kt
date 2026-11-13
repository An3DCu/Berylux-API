package com.anadcurukovic.berylux.api.account

import com.anadcurukovic.berylux.api.TestAddresses
import com.anadcurukovic.berylux.api.TestDatabase
import com.anadcurukovic.berylux.api.security.PasswordHasher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
class RegisterTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val hasher: PasswordHasher,
) {
    private val password = "correct horse battery"

    private fun freshEmail() = "player-${UUID.randomUUID()}@example.com"

    private fun body(
        email: String,
        password: String = this.password,
        name: String = "Marlowe",
        dayStartHour: Int = 4,
    ) = """
        {"email":"$email","password":"$password","profile":{"name":"$name","sigilSeed":7,"pickSeed":11,
        "timeZone":"Europe/Sarajevo","dayStartHour":$dayStartHour}}
    """.trimIndent()

    private fun register(json: String): ResultActionsDsl = mvc.post("/auth/register") {
        with(TestAddresses.from())
        contentType = MediaType.APPLICATION_JSON
        content = json
    }

    private fun accountsWith(email: String) = jdbc.sql("select count(*) from users where email = :email")
        .param("email", email)
        .query(Long::class.java)
        .single()

    @Test
    fun `a new account is stored with its profile, without signing in first`() {
        val email = freshEmail()
        register(body(email.uppercase(), name = "  Ana   Lee ")).andExpect { status { isCreated() } }

        val user = jdbc.sql("select id, password_hash from users where email = :email")
            .param("email", email)
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("password_hash") }
            .single()
        val profile = jdbc.sql(
            """
            select name, sigil_seed, pick_seed, time_zone, day_start_hour, on_leaderboard
            from profiles where user_id = :id
            """,
        )
            .param("id", user.first)
            .query { rs, _ ->
                listOf<Any>(
                    rs.getString(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getString(4),
                    rs.getInt(5),
                    rs.getBoolean(6),
                )
            }
            .single()
        assertEquals(listOf<Any>("Ana Lee", 7L, 11L, "Europe/Sarajevo", 4, false), profile)
        assertTrue(user.second.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"), user.second)
        assertFalse(user.second.contains(password))
    }

    @Test
    fun `the stored hash matches the password and nothing else`() {
        val email = freshEmail()
        register(body(email)).andExpect { status { isCreated() } }
        val hash = jdbc.sql("select password_hash from users where email = :email")
            .param("email", email)
            .query(String::class.java)
            .single()
        assertTrue(hasher.matches(password, hash))
        assertFalse(hasher.matches("correct horse battery ", hash))
    }

    @Test
    fun `a taken email can't be used, in any case`() {
        val email = freshEmail()
        register(body(email)).andExpect { status { isCreated() } }
        register(body(email.uppercase(), password = "another password")).andExpect {
            status { isConflict() }
            jsonPath("$.title") { value("This email can't be used") }
        }
        assertEquals(1, accountsWith(email))
    }

    @Test
    fun `each wrong field is named, and nothing is stored`() {
        val email = freshEmail()
        register(body(email, password = "short", dayStartHour = 24)).andExpect {
            status { isBadRequest() }
            jsonPath("$.title") { value("Some fields are wrong") }
            jsonPath("$.problems.password") { value("too_short") }
            jsonPath("$.problems['profile.dayStartHour']") { value("out_of_range") }
            jsonPath("$.problems.email") { doesNotExist() }
        }
        assertEquals(0, accountsWith(email))
    }

    @Test
    fun `an answer never repeats the password`() {
        val secret = "zq9-never-echo"
        val email = freshEmail()
        val body = register(body(email, password = secret, dayStartHour = 99)).andReturn().response.contentAsString
        assertFalse(body.contains(secret), body)
        val unreadable = register("{\"password\":\"$secret\"").andReturn().response.contentAsString
        assertFalse(unreadable.contains(secret), unreadable)
    }

    @Test
    fun `a body that isn't JSON is turned away plainly`() {
        register("{").andExpect {
            status { isBadRequest() }
            jsonPath("$.title") { value("The body can't be read") }
        }
    }

    @Test
    fun `only POST is open on the register route`() {
        mvc.get("/auth/register").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a body up to 8 KiB is read, and one byte more is turned away`() {
        val email = freshEmail()
        // A name long enough to fill the body to the limit; the name is then too long, not the body.
        val shell = body(email, name = "")
        val atLimit = body(email, name = "n".repeat(8 * 1024 - shell.length))
        assertEquals(8 * 1024, atLimit.toByteArray().size)
        register(atLimit).andExpect {
            status { isBadRequest() }
            jsonPath("$.problems['profile.name']") { value("too_long") }
        }
        register(body(email, name = "n".repeat(8 * 1024 - shell.length + 1))).andExpect {
            status { isEqualTo(413) }
            content { contentTypeCompatibleWith("application/problem+json") }
            jsonPath("$.title") { value("The body is too large") }
        }
        assertEquals(0, accountsWith(email))
    }
}
