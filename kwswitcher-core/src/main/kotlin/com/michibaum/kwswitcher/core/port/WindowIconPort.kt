package com.michibaum.kwswitcher.core.port

import com.michibaum.kwswitcher.core.domain.WindowInfo
import javax.swing.Icon

/**
 * Platform port for synchronous and asynchronous window icon resolution with LRU caching.
 */
interface WindowIconPort : AutoCloseable {
    val defaultIcon: Icon
    fun getIcon(window: WindowInfo): Icon
    fun getIconOrDefault(window: WindowInfo, onResolved: (() -> Unit)? = null): Icon
    fun clearCache()
}
