package com.anadcurukovic.berylux.api.security

import com.anadcurukovic.berylux.api.account.Accounts
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * An access token is good only while its account exists. A deleted account's token would otherwise
 * keep working for up to 15 minutes. One lookup by primary key on each signed-in request.
 */
@Component
class AccountStillExists(private val accounts: Accounts) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val userId = runCatching { UUID.fromString(token.subject) }.getOrNull()
        return if (userId != null && accounts.exists(userId)) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "No such account", null))
        }
    }
}
