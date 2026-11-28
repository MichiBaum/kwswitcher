package com.michibaum.kwswitcher.core.port

import com.michibaum.kwswitcher.core.domain.WindowInfo
import java.awt.Rectangle

/**
 * Platform port for enumerating open desktop windows and querying the active monitor's work area.
 */
interface WindowCatalogPort {
    fun enumerateOpenWindows(): List<WindowInfo>
    fun getActiveMonitorWorkArea(): Rectangle?
}
