package com.calisthenics.diagnostic.composition

import com.calisthenics.diagnostic.contract.Uuid
import java.security.SecureRandom

/** Shared Android composition source for reserved M0.5 synthetic session identities. */
object M05SessionIdGenerator {
    private val random = SecureRandom()

    fun next(): Uuid {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        val suffix = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Uuid("00000000-0000-4000-8000-$suffix")
    }
}
