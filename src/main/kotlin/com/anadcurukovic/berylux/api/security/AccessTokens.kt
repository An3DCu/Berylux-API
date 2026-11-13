package com.anadcurukovic.berylux.api.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The key that signs access tokens: `BERYLUX_TOKEN_SECRET`, at least 32 random bytes in Base64.
 * Without one, or with a short one, the API stops at start instead of running on a weak key.
 */
object SigningKey {
    const val MIN_BYTES = 32

    fun from(secret: String): SecretKey {
        val bytes = try {
            Base64.getDecoder().decode(secret.trim())
        } catch (_: IllegalArgumentException) {
            error("BERYLUX_TOKEN_SECRET isn't Base64")
        }
        check(bytes.size >= MIN_BYTES) { "BERYLUX_TOKEN_SECRET must be at least $MIN_BYTES random bytes in Base64" }
        return SecretKeySpec(bytes, "HmacSHA256")
    }
}

/** Signs and checks access tokens: HS256 JWTs from this API, for 15 minutes. */
@Configuration
class AccessTokenKeys(@Value("\${berylux.token-secret:}") secret: String) {
    private val key = SigningKey.from(secret)

    /** Checks the signature, the times and the issuer, then that the account still exists. */
    @Bean
    fun jwtDecoder(accountExists: AccountStillExists): JwtDecoder = NimbusJwtDecoder.withSecretKey(key)
        .macAlgorithm(MacAlgorithm.HS256)
        .build()
        .apply {
            val signedHere = JwtValidators.createDefaultWithIssuer(AccessTokens.ISSUER)
            setJwtValidator(DelegatingOAuth2TokenValidator(signedHere, accountExists))
        }

    @Bean
    fun jwtEncoder(): JwtEncoder = NimbusJwtEncoder(ImmutableSecret<SecurityContext>(key))

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

@Component
class AccessTokens(private val encoder: JwtEncoder, private val clock: Clock) {
    /** A token naming the player and nothing else: no email, no name. */
    fun issue(userId: UUID): String {
        val now = clock.instant()
        val claims = JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plus(LIFETIME))
            .id(UUID.randomUUID().toString())
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return checkNotNull(encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue)
    }

    companion object {
        const val ISSUER = "berylux-api"
        val LIFETIME: Duration = Duration.ofMinutes(15)
    }
}
