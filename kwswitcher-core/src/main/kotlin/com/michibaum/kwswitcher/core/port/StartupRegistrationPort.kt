package com.michibaum.kwswitcher.core.port

/**
 * Platform port managing OS login auto-start registration.
 */
interface StartupRegistrationPort {
    fun isRegistered(): Boolean
    fun setRegistered(enabled: Boolean): Boolean
}
