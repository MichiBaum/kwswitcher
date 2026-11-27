package com.michibaum.kwswitcher.core.port

/**
 * Typed outcome of a native platform operation (`DX-5`).
 */
sealed interface NativeResult {
    data object Ok : NativeResult

    data class Failed(val operation: String, val errorCode: Int = 0, val detail: String = "") : NativeResult

    val isSuccess: Boolean
        get() = this is Ok
}
