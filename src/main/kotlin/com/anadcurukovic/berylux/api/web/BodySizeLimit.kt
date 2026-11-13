package com.anadcurukovic.berylux.api.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * Turns away any request body over [MAX_BYTES], before sign-in is checked and before anything
 * reads it. A body sent without a length is cut off at the same size while it's read.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class BodySizeLimit : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val length = request.contentLengthLong
        if (length > MAX_BYTES) {
            // Written here, in the same shape as every other problem: nothing past this filter runs.
            response.status = HttpStatus.CONTENT_TOO_LARGE.value()
            response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write("""{"status":413,"title":"The body is too large"}""")
            return
        }
        chain.doFilter(if (length < 0) LimitedBody(request) else request, response)
    }

    companion object {
        const val MAX_BYTES = 8L * 1024
    }
}

/** Thrown while reading a body that has gone past [BodySizeLimit.MAX_BYTES]. */
class BodyTooLargeException : IOException("The body is over the size limit")

private class LimitedBody(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
    private val stream by lazy { LimitedStream(request.inputStream) }

    override fun getInputStream(): ServletInputStream = stream

    override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(stream, characterEncoding ?: "UTF-8"))
}

private class LimitedStream(private val inner: ServletInputStream) : ServletInputStream() {
    private var read = 0L

    override fun read(): Int {
        val byte = inner.read()
        if (byte >= 0) count(1)
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = inner.read(b, off, len)
        if (n > 0) count(n.toLong())
        return n
    }

    private fun count(n: Long) {
        read += n
        if (read > BodySizeLimit.MAX_BYTES) throw BodyTooLargeException()
    }

    override fun isFinished() = inner.isFinished

    override fun isReady() = inner.isReady

    override fun setReadListener(listener: ReadListener) = inner.setReadListener(listener)
}
