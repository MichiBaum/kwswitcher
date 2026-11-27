package com.michibaum.kwswitcher.core.port

/**
 * Platform port enforcing single-instance execution per runtime profile.
 */
interface SingleInstancePort : AutoCloseable {
    fun tryAcquire(): Boolean
}
