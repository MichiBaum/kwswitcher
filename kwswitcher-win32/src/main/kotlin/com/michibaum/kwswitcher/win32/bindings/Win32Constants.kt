package com.michibaum.kwswitcher.win32.bindings

/**
 * Win32 API constants shared across native adapters.
 */
object Win32Constants {
    const val WH_KEYBOARD_LL: Int = 13
    const val HC_ACTION: Int = 0
    const val PM_NOREMOVE: Int = 0x0000

    const val WM_CLOSE: Int = 0x0010
    const val WM_QUIT: Int = 0x0012
    const val WM_GETICON: Int = 0x007F
    const val WM_KEYDOWN: Int = 0x0100
    const val WM_KEYUP: Int = 0x0101
    const val WM_SYSKEYDOWN: Int = 0x0104
    const val WM_SYSKEYUP: Int = 0x0105

    const val ICON_SMALL: Long = 0L
    const val ICON_BIG: Long = 1L
    const val ICON_SMALL2: Long = 2L

    const val GCLP_HICON: Int = -14
    const val GCLP_HICONSM: Int = -34

    const val SMTO_ABORTIFHUNG: Int = 0x0002

    const val VK_BACK: Int = 0x08
    const val VK_TAB: Int = 0x09
    const val VK_RETURN: Int = 0x0D
    const val VK_SHIFT: Int = 0x10
    const val VK_CONTROL: Int = 0x11
    const val VK_MENU: Int = 0x12
    const val VK_ESCAPE: Int = 0x1B
    const val VK_SPACE: Int = 0x20
    const val VK_UP: Int = 0x26
    const val VK_DOWN: Int = 0x28
    const val VK_DELETE: Int = 0x2E
    const val VK_LWIN: Int = 0x5B
    const val VK_RWIN: Int = 0x5C
    const val VK_NUMPAD0: Int = 0x60
    const val VK_NUMPAD1: Int = 0x61
    const val VK_NUMPAD2: Int = 0x62
    const val VK_NUMPAD3: Int = 0x63
    const val VK_NUMPAD4: Int = 0x64
    const val VK_NUMPAD5: Int = 0x65
    const val VK_NUMPAD6: Int = 0x66
    const val VK_NUMPAD7: Int = 0x67
    const val VK_NUMPAD8: Int = 0x68
    const val VK_NUMPAD9: Int = 0x69
    const val VK_LSHIFT: Int = 0xA0
    const val VK_RSHIFT: Int = 0xA1
    const val VK_LCONTROL: Int = 0xA2
    const val VK_RCONTROL: Int = 0xA3
    const val VK_LMENU: Int = 0xA4
    const val VK_RMENU: Int = 0xA5
    const val VK_OEM_3: Int = 0xC0
    const val VK_UNLOCK_FOREGROUND: Int = 0xFF

    const val LLKHF_INJECTED: Int = 0x10
    const val LLKHF_ALTDOWN: Int = 0x20

    const val GWL_STYLE: Int = -16
    const val GWL_EXSTYLE: Int = -20
    const val WS_DISABLED: Long = 0x08000000L
    const val WS_EX_TOOLWINDOW: Long = 0x00000080L
    const val WS_EX_APPWINDOW: Long = 0x00040000L
    const val WS_EX_NOACTIVATE: Long = 0x08000000L

    const val DWMWA_CLOAKED: Int = 14
    const val PROCESS_TERMINATE: Int = 0x0001
    const val PROCESS_QUERY_LIMITED_INFORMATION: Int = 0x1000

    const val SW_MINIMIZE: Int = 6
    const val SW_RESTORE: Int = 9
    const val INPUT_KEYBOARD: Int = 1
    const val KEYEVENTF_KEYUP: Int = 0x0002

    const val MONITOR_DEFAULTTONEAREST: Int = 2
    const val MONITORINFOF_PRIMARY: Int = 1
    const val DIB_RGB_COLORS: Int = 0
    const val BI_RGB: Int = 0

    const val ERROR_SUCCESS: Int = 0
    const val ERROR_FILE_NOT_FOUND: Int = 2
    const val ERROR_ACCESS_DENIED: Int = 5
    const val ERROR_ALREADY_EXISTS: Int = 183
    const val ERROR_MORE_DATA: Int = 234
    const val ERROR_INVALID_WINDOW_HANDLE: Int = 1400

    const val FORMAT_MESSAGE_IGNORE_INSERTS: Int = 0x00000200
    const val FORMAT_MESSAGE_FROM_SYSTEM: Int = 0x00001000
    const val LANG_ENGLISH_US: Int = 0x0409

    const val REG_SZ: Int = 1
    const val RRF_RT_REG_SZ: Int = 0x00000002
    val HKEY_CURRENT_USER: Long = 0x80000001.toInt().toLong()
}
