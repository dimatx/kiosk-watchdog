package com.shymoose.wifiwatchdog

/** Exact system-resource text, including this app's label, for one bounded request. */
internal data class WifiEnablePrompt(
    val title: String,
    val message: String,
    val allow: String,
    val expiresAt: Long
) {
    fun matches(packageName: String?, texts: List<String>, now: Long): Boolean =
        now < expiresAt && packageName == PACKAGE &&
            title in texts && message in texts

    companion object {
        const val PACKAGE = "com.android.wifi.dialog"
    }
}
