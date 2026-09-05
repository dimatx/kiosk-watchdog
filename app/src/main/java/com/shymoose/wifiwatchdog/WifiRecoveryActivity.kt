package com.shymoose.wifiwatchdog

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Makes a recovery confirmation reachable without attempting to unlock a secured device. */
class WifiRecoveryActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var request: WakeRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = pending
        if (request == null) {
            finish()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    override fun onResume() {
        super.onResume()
        val wake = request ?: return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            if (keyguard.isDeviceSecure) {
                complete(false)
                return
            }
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissError() = complete(false)
                override fun onDismissCancelled() = complete(false)
            })
        }
        val power = getSystemService(PowerManager::class.java)
        val check = object : Runnable {
            override fun run() {
                if (pending !== wake || SystemClock.elapsedRealtime() >= wake.deadline) {
                    complete(false)
                } else if (power.isInteractive && !keyguard.isKeyguardLocked) {
                    complete(true)
                } else {
                    handler.postDelayed(this, 100L)
                }
            }
        }
        handler.post(check)
    }

    private fun complete(success: Boolean) {
        handler.removeCallbacksAndMessages(null)
        request?.let {
            it.success = success
            it.done.countDown()
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private class WakeRequest {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        val done = CountDownLatch(1)
        @Volatile var success = false
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        @Volatile private var pending: WakeRequest? = null

        /** Called on the recovery worker, before asking Android to show the Wi-Fi dialog. */
        internal fun prepare(context: Context): Boolean {
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (power.isInteractive && !keyguard.isKeyguardLocked) return true
            if (keyguard.isDeviceSecure) {
                EventLog.add(context, EventLevel.ERROR, "Wi-Fi confirmation blocked by a secured lock screen")
                return false
            }
            val service = InstallAutoClickService.bound
            if (service == null) {
                EventLog.add(context, EventLevel.ERROR, "Cannot expose Wi-Fi confirmation: accessibility service disconnected")
                return false
            }
            val wake = WakeRequest()
            pending = wake
            try {
                Handler(Looper.getMainLooper()).post {
                    if (pending === wake) {
                        try {
                            service.startActivity(Intent(service, WifiRecoveryActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: SecurityException) {
                            EventLog.add(context, EventLevel.ERROR, "Wi-Fi recovery wake denied: ${e.message}")
                            wake.done.countDown()
                        }
                    }
                }
                val ready = wake.done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) && wake.success
                if (!ready) {
                    EventLog.add(context, EventLevel.ERROR, "Could not expose the Wi-Fi confirmation; recovery was not completed")
                }
                return ready
            } finally {
                if (pending === wake) pending = null
            }
        }
    }
}
