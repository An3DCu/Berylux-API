package com.anadcurukovic.berylux.api.account

import com.anadcurukovic.berylux.api.TestAddresses
import com.anadcurukovic.berylux.api.TestDatabase
import com.jayway.jsonpath.JsonPath
import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase::class)
class SignInTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val encoder: JwtEncoder,
) {
    private val password = "correct horse battery"

    private companion object {
        const val TOKENS_OF = "from refresh_tokens t join users u on u.id = t.user_id where u.email = :email"
    }

    private data class Pair(val access: String, val refresh: String)

    private fun post(path: String, json: String, bearer: String? = null): ResultActionsDsl = mvc.post(path) {
        with(TestAddresses.from())
        contentType = MediaType.APPLICATION_JSON
        content = json
        bearer?.let { header("Authorization", "Bearer $it") }
    }

    private fun pairOf(body: String) = Pair(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"))

    private fun register(email: String): Pair {
        val json = """
            {"email":"$email","password":"$password","profile":{"name":"Marlowe","sigilSeed":7,"pickSeed":11,
            "timeZone":"Europe/Sarajevo","dayStartHour":4}}
        """.trimIndent()
        val answer = post("/auth/register", json).andExpect { status { isCreated() } }.andReturn()
        return pairOf(answer.response.contentAsString)
    }

    private fun freshEmail() = "player-${UUID.randomUUID()}@example.com"

    private fun login(email: String, password: String = this.password) =
        post("/auth/login", """{"email":"$email","password":"$password"}""")

    private fun refresh(token: String) = post("/auth/refresh", """{"refreshToken":"$token"}""")

    private fun sha256(text: String) =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    @Test
    fun `register signs the player in, and the answer isn't kept by caches`() {
        val json = """
            {"email":"${freshEmail()}","password":"$password","profile":{"name":"Marlowe","sigilSeed":7,"pickSeed":11,
            "timeZone":"Europe/Sarajevo","dayStartHour":4}}
        """.trimIndent()
        post("/auth/register", json).andExpect {
            status { isCreated() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.tokenType") { value("Bearer") }
            // 15 minutes and 30 days, in seconds.
            jsonPath("$.expiresIn") { value(900) }
            jsonPath("$.refreshExpiresIn") { value(2_592_000) }
        }
    }

    @Test
    fun `an access token names the player, from this API, for 15 minutes, and nothing else`() {
        val email = freshEmail()
        val access = register(email).access
        val userId = jdbc.sql("select id from users where email = :email")
            .param("email", email)
            .query(UUID::class.java)
            .single()
        val claims = String(Base64.getUrlDecoder().decode(access.split(".")[1]))
        assertEquals(userId.toString(), JsonPath.read(claims, "$.sub"))
        assertEquals("berylux-api", JsonPath.read(claims, "$.iss"))
        assertEquals(900, JsonPath.read<Int>(claims, "$.exp") - JsonPath.read<Int>(claims, "$.iat"))
        assertFalse(claims.contains(email), claims)
        assertFalse(claims.contains("Marlowe"), claims)
    }

    @Test
    fun `an access token gets past the closed door`() {
        val access = register(freshEmail()).access
        mvc.get("/cases").andExpect { status { isUnauthorized() } }
        // Past security there's no such route yet: 404, not 401.
        mvc.get("/cases") { header("Authorization", "Bearer $access") }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `log in takes the email in any case and starts a new chain`() {
        val email = freshEmail()
        val first = register(email)
        val body = login(email.uppercase()).andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
        }.andReturn().response.contentAsString
        assertNotEquals(first.refresh, pairOf(body).refresh)
        val families = jdbc.sql(
            "select count(distinct family_id) $TOKENS_OF",
        ).param("email", email).query(Long::class.java).single()
        assertEquals(2, families)
    }

    @Test
    fun `a wrong password and an unknown email get the same answer`() {
        val email = freshEmail()
        register(email)
        val wrongPassword = login(email, "not the password").andReturn().response
        val unknownEmail = login(freshEmail()).andReturn().response
        assertEquals(401, wrongPassword.status)
        assertEquals(wrongPassword.status, unknownEmail.status)
        assertEquals(wrongPassword.contentAsString, unknownEmail.contentAsString)
        assertEquals("Wrong email or password", JsonPath.read(wrongPassword.contentAsString, "$.title"))
    }

    @Test
    fun `log in names what's missing`() {
        post("/auth/login", "{}").andExpect {
            status { isBadRequest() }
            jsonPath("$.problems.email") { value("missing") }
            jsonPath("$.problems.password") { value("missing") }
        }
    }

    @Test
    fun `a refresh token is stored only as its SHA-256`() {
        val email = freshEmail()
        val refresh = register(email).refresh
        val hashes = jdbc.sql("select token_hash $TOKENS_OF")
            .param("email", email)
            .query(String::class.java)
            .list()
        assertEquals(listOf(sha256(refresh)), hashes)
    }

    @Test
    fun `a refresh gives a new pair, and the old token stops working`() {
        val first = register(freshEmail())
        val next = pairOf(refresh(first.refresh).andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertNotEquals(first.refresh, next.refresh)
        refresh(next.refresh).andExpect { status { isOk() } }
    }

    @Test
    fun `an old refresh token that comes back ends its whole chain`() {
        val first = register(freshEmail())
        val next = pairOf(refresh(first.refresh).andReturn().response.contentAsString)
        refresh(first.refresh).andExpect {
            status { isUnauthorized() }
            jsonPath("$.title") { value("Sign in again") }
        }
        // The copy came back: the token the phone got from the honest refresh stops working too.
        refresh(next.refresh).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `each refresh token lasts 30 days from when it was made`() {
        val email = freshEmail()
        refresh(register(email).refresh)
        val lifetimes = jdbc.sql(
            "select t.created_at, t.expires_at $TOKENS_OF",
        )
            .param("email", email)
            .query { rs, _ ->
                Duration.between(
                    rs.getObject("created_at", OffsetDateTime::class.java),
                    rs.getObject("expires_at", OffsetDateTime::class.java),
                )
            }
            .list()
        assertEquals(listOf(Duration.ofDays(30), Duration.ofDays(30)), lifetimes)
    }

    @Test
    fun `an expired refresh token is turned away`() {
        val refresh = register(freshEmail()).refresh
        jdbc.sql("update refresh_tokens set expires_at = now() - interval '1 second' where token_hash = :hash")
            .param("hash", sha256(refresh))
            .update()
        refresh(refresh).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `log out ends this phone's chain and no other`() {
        val email = freshEmail()
        val phone = register(email)
        val tablet = pairOf(login(email).andReturn().response.contentAsString)
        post("/auth/logout", """{"refreshToken":"${phone.refresh}"}""", bearer = phone.access)
            .andExpect { status { isNoContent() } }
        refresh(phone.refresh).andExpect { status { isUnauthorized() } }
        refresh(tablet.refresh).andExpect { status { isOk() } }
    }

    @Test
    fun `log out needs an access token`() {
        val refresh = register(freshEmail()).refresh
        post("/auth/logout", """{"refreshToken":"$refresh"}""").andExpect { status { isUnauthorized() } }
        refresh(refresh).andExpect { status { isOk() } }
    }

    @Test
    fun `nobody can log out someone else's phone`() {
        val alex = register(freshEmail())
        val sam = register(freshEmail())
        post("/auth/logout", """{"refreshToken":"${alex.refresh}"}""", bearer = sam.access)
            .andExpect { status { isNoContent() } }
        refresh(alex.refresh).andExpect { status { isOk() } }
    }

    @Test
    fun `an access token that ran out, came from elsewhere, or isn't signed is turned away`() {
        val userId = UUID.randomUUID().toString()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        fun claims(issuer: String, issued: Instant) = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(userId)
            .issuedAt(issued)
            .expiresAt(issued.plus(Duration.ofMinutes(15)))
            .build()
        fun sign(with: JwtEncoder, claims: JwtClaimsSet) =
            with.encode(JwtEncoderParameters.from(header, claims)).tokenValue

        // Ran out 5 minutes ago, past the one minute of clock difference allowed.
        val expired = sign(encoder, claims("berylux-api", Instant.now().minus(Duration.ofMinutes(20))))
        val wrongIssuer = sign(encoder, claims("someone-else", Instant.now()))
        val otherKey = NimbusJwtEncoder(
            ImmutableSecret<SecurityContext>(SecretKeySpec(ByteArray(32) { 1 }, "HmacSHA256")),
        )
        val forged = sign(otherKey, claims("berylux-api", Instant.now()))
        val claimsJson = """{"iss":"berylux-api","sub":"$userId","exp":${Instant.now().epochSecond + 900}}"""
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claimsJson.toByteArray())
        val unsigned = "eyJhbGciOiJub25lIn0.$payload."

        for (token in listOf(expired, wrongIssuer, forged, unsigned)) {
            mvc.get("/cases") { header("Authorization", "Bearer $token") }.andExpect { status { isUnauthorized() } }
        }
    }
}
