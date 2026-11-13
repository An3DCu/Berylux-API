package com.anadcurukovic.berylux.api.security

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Hashes passwords with Argon2id at OWASP's recommended minimum: 19 MiB of memory, 2 passes and
 * one lane. Each hash holds its memory for a moment. Only as many run at once as the server has
 * processors, and a burst of sign-ins waits its turn instead of taking all the memory.
 */
@Component
class PasswordHasher {
    private val encoder = Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, LANES, MEMORY_KIB, PASSES)
    private val permits = Semaphore(Runtime.getRuntime().availableProcessors())

    fun hash(password: String): String = oneAtATime { checkNotNull(encoder.encode(password)) }

    fun matches(password: String, hash: String): Boolean = oneAtATime { encoder.matches(password, hash) }

    private fun <T> oneAtATime(work: () -> T): T {
        if (!permits.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) throw ServerBusyException()
        try {
            return work()
        } finally {
            permits.release()
        }
    }

    companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val LANES = 1
        const val MEMORY_KIB = 19 * 1024
        const val PASSES = 2
        const val WAIT_SECONDS = 5L
    }
}

/** Every slot for a password hash stayed taken for the whole wait. */
class ServerBusyException : RuntimeException("Too many passwords being hashed at once")
