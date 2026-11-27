package com.michibaum.kwswitcher.core.port

import com.michibaum.kwswitcher.core.domain.OverlayConstants
import com.michibaum.kwswitcher.core.domain.WindowHandle

/**
 * Platform port for focusing, closing, minimizing, and terminating windows/processes.
 */
interface WindowControlPort {
    fun activate(handle: WindowHandle): NativeResult
    fun activateOverlay(overlayTitle: String = OverlayConstants.OVERLAY_WINDOW_TITLE): NativeResult
    fun closeGracefully(handle: WindowHandle): NativeResult
    fun minimize(handle: WindowHandle): NativeResult
    fun terminateProcess(pid: Int): NativeResult
}
