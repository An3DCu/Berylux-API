package com.anadcurukovic.berylux.api.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.TimeMeter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * How often anyone can try the sign-in routes. Each window is fixed: it starts at the first try and
 * refills all at once when it ends. Where a limit has two windows, a try needs room in both.
 */
// The numbers are the limits themselves; this table is the one place they're named.
@Suppress("MagicNumber")
enum class Limit(val windows: List<Pair<Long, Duration>>) {
    /** Per address. Register is the one route that says an email has an account. */
    REGISTER(listOf(3L to Duration.ofHours(1), 10L to Duration.ofDays(1))),

    /** Per address: one machine trying many emails. */
    LOGIN_ADDRESS(listOf(5L to Duration.ofMinutes(1), 20L to Duration.ofHours(1))),

    /** Per email, from anywhere: many machines guessing one password. Counted for unknown emails too. */
    LOGIN_EMAIL(listOf(5L to Duration.ofMinutes(15))),

    /** Per address. A phone refreshes once every 15 minutes. */
    REFRESH(listOf(10L to Duration.ofMinutes(1))),

    /** Per account: a stolen access token can't be used to guess the password on the delete route. */
    DELETE_ACCOUNT(listOf(5L to Duration.ofMinutes(15))),
    ;

    val longestWindow: Duration = windows.maxOf { it.second }
}

/**
 * The buckets, in memory, one per address or email and limit. Each kind is held in its own
 * Caffeine cache of at most [MAX_KEYS]. A flood of new addresses pushes out old buckets and can't
 * fill the memory. A bucket nobody has used for its longest window is dropped: it would be full again.
 */
class RateLimits(private val time: TimeMeter, maxKeys: Long = MAX_KEYS) {
    private val buckets: Map<Limit, Cache<String, Bucket>> = Limit.entries.associateWith { limit ->
        Caffeine.newBuilder()
            .maximumSize(maxKeys)
            .expireAfterAccess(limit.longestWindow)
            .build()
    }

    /** Takes one try. Null when there was room, or else how many seconds until there is. */
    fun take(limit: Limit, key: String): Long? {
        val bucket = checkNotNull(buckets.getValue(limit).get(key) { newBucket(limit) })
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) return null
        return Math.ceilDiv(probe.nanosToWaitForRefill, TimeUnit.SECONDS.toNanos(1)).coerceAtLeast(1)
    }

    /** How many buckets [limit] holds now, after Caffeine has pushed out what's over the bound. */
    internal fun keys(limit: Limit): Long = buckets.getValue(limit).run {
        cleanUp()
        estimatedSize()
    }

    private fun newBucket(limit: Limit): Bucket {
        val builder = Bucket.builder().withCustomTimePrecision(time)
        for ((tries, window) in limit.windows) {
            builder.addLimit(Bandwidth.builder().capacity(tries).refillIntervally(tries, window).build())
        }
        return builder.build()
    }

    companion object {
        const val MAX_KEYS = 50_000L
    }
}

@Configuration
class RateLimitSetup {
    @Bean
    fun rateLimits(): RateLimits = RateLimits(TimeMeter.SYSTEM_NANOTIME)
}
