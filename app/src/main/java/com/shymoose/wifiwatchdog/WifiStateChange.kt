package com.shymoose.wifiwatchdog

/** API acceptance may mean "a confirmation dialog was queued", not a state change. */
internal class WifiStateChange(
    private val request: (Boolean) -> Boolean,
    private val isInState: (Boolean) -> Boolean,
    private val now: () -> Long,
    private val pause: (Long) -> Unit,
    private val poll: () -> Unit
) {
    fun apply(enabled: Boolean, timeoutMs: Long): Boolean {
        if (isInState(enabled)) return true
        if (!request(enabled)) return false
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            if (isInState(enabled)) return true
            poll()
            pause(250L)
        }
        return isInState(enabled)
    }
}
