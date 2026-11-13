package com.anadcurukovic.berylux.api.web

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A body sent without a length, as chunked uploads are, is counted while it's read. */
class BodySizeLimitTest {
    private fun withoutLength(bytes: Int) = object : MockHttpServletRequest("POST", "/auth/register") {
        override fun getContentLengthLong() = -1L
    }.apply { setContent(ByteArray(bytes) { 'x'.code.toByte() }) }

    private fun readAll(bytes: Int, read: (jakarta.servlet.ServletRequest) -> Int): Int {
        var total = 0
        val chain = FilterChain { request, _ -> total = read(request) }
        BodySizeLimit().doFilter(withoutLength(bytes), MockHttpServletResponse(), chain)
        return total
    }

    @Test
    fun `a body without a length is read up to the limit`() {
        assertEquals(8 * 1024, readAll(8 * 1024) { it.inputStream.readAllBytes().size })
        assertEquals(8 * 1024, readAll(8 * 1024) { it.reader.readText().length })
    }

    @Test
    fun `a body without a length stops one byte past the limit`() {
        assertFailsWith<BodyTooLargeException> { readAll(8 * 1024 + 1) { it.inputStream.readAllBytes().size } }
        assertFailsWith<BodyTooLargeException> { readAll(8 * 1024 + 1) { it.reader.readText().length } }
    }
}
