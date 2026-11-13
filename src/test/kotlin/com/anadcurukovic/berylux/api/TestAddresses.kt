package com.anadcurukovic.berylux.api

import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client addresses for tests. The rate limits count per address, and every test shares one set of
 * buckets. A test that isn't about the limits sends each request from an address of its own.
 */
object TestAddresses {
    private val next = AtomicInteger(1)

    fun fresh(): String {
        val n = next.getAndIncrement()
        return "10.${n shr 16 and 255}.${n shr 8 and 255}.${n and 255}"
    }

    fun from(address: String = fresh()) = RequestPostProcessor { it.apply { remoteAddr = address } }
}
