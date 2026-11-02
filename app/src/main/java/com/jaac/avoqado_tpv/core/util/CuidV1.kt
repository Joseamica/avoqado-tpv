package com.jaac.avoqado_tpv.core.util

import java.security.SecureRandom

/**
 * JVM port of cuid v1 (https://github.com/paralleldrive/cuid), Eric Elliott, MIT.
 * Retains timestamp + counter + process fingerprint + two random blocks; no storage/auth/network required.
 * This platform's fingerprint is securely seeded once per process instead of browser/Node metadata.
 *
 * Copyright (c) 2012 Eric Elliott
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions
 * of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
internal class CuidV1Generator(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val fingerprint: String = random.nextInt(BLOCK_LIMIT).toString(36).padStart(4, '0'),
) {
    private var counter = 0

    @Synchronized fun next(): String {
        val timestamp = clock().toString(36).padStart(8, '0')
        require(timestamp.length == 8 && !timestamp.startsWith('-')) { "Cuid v1 requires an eight-character epoch timestamp" }
        require(fingerprint.matches(Regex("[a-z0-9]{4}")))
        val sequence = counter.toString(36).padStart(4, '0')
        counter = (counter + 1) % BLOCK_LIMIT
        return "c$timestamp$sequence$fingerprint${block()}${block()}"
    }

    private fun block() = random.nextInt(BLOCK_LIMIT).toString(36).padStart(4, '0')
    private companion object { const val BLOCK_LIMIT = 36 * 36 * 36 * 36 }
}

object CuidV1 {
    private val generator = CuidV1Generator()
    fun next(): String = generator.next()
}
