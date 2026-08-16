package com.shymoose.wifiwatchdog

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

/**
 * A small flight recorder, written to disk on every check.
 *
 * A display that freezes leaves nothing behind. The event log is kept in shared
 * preferences and written asynchronously, so the entries from the minutes before
 * a freeze - the only ones worth having - are exactly the ones rolled back when
 * the power is pulled. The system records no crash for a hang, and adb over the
 * network does not survive the restart needed to recover the device.
 *
 * So each sample is appended and flushed to storage as it is taken. That costs a
 * few hundred bytes per check and buys a picture of the minutes leading up to a
 * freeze: whether memory was draining, the temperature climbing, the load
 * rising, or the signal fading.
 *
 * Fields are positional and terse because this is written thousands of times a
 * day and read by hand perhaps twice a year.
 */
object Vitals {

    private const val FILE = "vitals.log"

    /** Roughly a day at the default interval, and small enough to read in one go. */
    private const val MAX_BYTES = 96 * 1024L
    private const val KEEP_BYTES = 48 * 1024

    /** One reading. Absent values are recorded as an empty field rather than a zero. */
    data class Sample(
        val at: Long,
        val uptimeSec: Long,
        val memAvailMb: Int,
        val load1: String,
        val tempC: String,
        val rssi: Int?,
        val linkMbps: Int?,
        val stage: Int,
        val online: Boolean,
        val wifiEnabled: Boolean = true,
        val iface: String = "",
        val bssid: String = "",
        val rxMb: Long = 0,
        val mv: Int = 0,
        val wlanCrashes: Int = 0,
        val wlanSubsys: String = "",
        val supplicant: String = "",
        val dataFreeMb: Int = -1,
        val scanCount: Int = -1
    ) {
        fun encode(): String = listOf(
            at.toString(),
            uptimeSec.toString(),
            memAvailMb.toString(),
            load1,
            tempC,
            rssi?.toString() ?: "",
            linkMbps?.toString() ?: "",
            stage.toString(),
            if (online) "1" else "0",
            if (wifiEnabled) "1" else "0",
            iface,
            bssid,
            rxMb.toString(),
            mv.toString(),
            wlanCrashes.toString(),
            wlanSubsys,
            supplicant,
            dataFreeMb.toString(),
            scanCount.toString()
        ).joinToString(",")

        /** Readable form, for the report sent after a freeze. */
        fun describe(): String {
            val clock = LogEvent(at, EventLevel.INFO, "").formattedTime()
            val link = when {
                !wifiEnabled -> "wifi off"
                rssi != null -> "$rssi dBm"
                else -> "no link"
            }
            val radio = if (iface.isEmpty()) " iface gone" else " $iface"
            val ap = if (bssid.isEmpty()) "" else " ap $bssid"
            // Only worth the width when it is saying something.
            val radioFw = buildString {
                if (wlanCrashes > 0) append("  fw-crashes $wlanCrashes")
                // Printed even when healthy: an empty value means the counter
                // could not be read at all, which is worth knowing before
                // relying on it to explain a freeze.
                append(if (wlanSubsys.isEmpty()) "  wlan ?" else "  wlan ${wlanSubsys.lowercase()}")
            }
            // Suppressed while healthy, but an unreadable value is shown, so an
            // empty column cannot be mistaken for a working supplicant.
            val supp = when {
                supplicant.isEmpty() -> "  supp ?"
                supplicant == "COMPLETED" -> ""
                else -> "  " + supplicant.lowercase()
            }
            // Slow-moving, so only worth the width when it is a problem, or when
            // it could not be read and so cannot be relied on.
            val disk = when {
                dataFreeMb < 0 -> "  disk ?"
                dataFreeMb < LOW_DISK_MB -> "  disk ${dataFreeMb}MB"
                else -> ""
            }
            // The count of visible access points. Printed whenever it is not a
            // healthy positive number, since zero is the whole point of having it.
            val seen = when {
                scanCount > 0 -> "  aps $scanCount"
                scanCount == 0 -> "  APS NONE"
                else -> "  aps ?"
            }
            return "$clock  up ${uptimeSec / 60}m  mem ${memAvailMb}MB  load $load1  " +
                "${tempC}C  $link$radio$ap  rx ${rxMb}MB  ${mv}mV  stage $stage$radioFw$supp$seen$disk"
        }

        companion object {
            /** Below this, storage is worth mentioning on every sample. */
            private const val LOW_DISK_MB = 512

            fun decode(line: String): Sample? {
                val f = line.split(',')
                if (f.size < 9) return null
                return Sample(
                    at = f[0].toLongOrNull() ?: return null,
                    uptimeSec = f[1].toLongOrNull() ?: 0,
                    memAvailMb = f[2].toIntOrNull() ?: 0,
                    load1 = f[3],
                    tempC = f[4],
                    rssi = f[5].toIntOrNull(),
                    linkMbps = f[6].toIntOrNull(),
                    stage = f[7].toIntOrNull() ?: 0,
                    online = f[8] == "1",
                    // Later additions: older lines simply do not carry them.
                    wifiEnabled = f.getOrNull(9) != "0",
                    iface = f.getOrNull(10).orEmpty(),
                    bssid = f.getOrNull(11).orEmpty(),
                    rxMb = f.getOrNull(12)?.toLongOrNull() ?: 0,
                    mv = f.getOrNull(13)?.toIntOrNull() ?: 0,
                    wlanCrashes = f.getOrNull(14)?.toIntOrNull() ?: 0,
                    wlanSubsys = f.getOrNull(15).orEmpty(),
                    supplicant = f.getOrNull(16).orEmpty(),
                    dataFreeMb = f.getOrNull(17)?.toIntOrNull() ?: -1,
                    scanCount = f.getOrNull(18)?.toIntOrNull() ?: -1
                )
            }
        }
    }

    /**
     * Takes a reading and puts it on disk.
     *
     * Flushed through to storage deliberately: a buffered write is worth nothing
     * for the one event this exists to explain.
     */
    fun record(context: Context, wifi: WifiStatus?, stage: Int, online: Boolean) {
        val wlan = readWlanSubsys()
        val sample = Sample(
            at = System.currentTimeMillis(),
            uptimeSec = readUptimeSec(),
            memAvailMb = readMemAvailableMb(),
            load1 = readLoad1(),
            tempC = readMaxTempC(),
            rssi = wifi?.rssi,
            linkMbps = wifi?.linkSpeedMbps,
            stage = stage,
            online = online,
            wifiEnabled = wifi?.wifiEnabled ?: false,
            iface = readIfaceState(),
            bssid = wifi?.bssid?.takeLast(8).orEmpty(),
            rxMb = readRxBytes() / (1024 * 1024),
            mv = readMilliVolts(),
            wlanCrashes = wlan?.second ?: 0,
            wlanSubsys = wlan?.first.orEmpty(),
            supplicant = wifi?.supplicant.orEmpty(),
            dataFreeMb = readDataFreeMb(),
            scanCount = wifi?.scanCount ?: -1
        )
        runCatching {
            val file = File(context.filesDir, FILE)
            if (file.length() > MAX_BYTES) roll(file)
            FileOutputStream(file, true).use { out ->
                out.write((sample.encode() + "\n").toByteArray())
                out.flush()
                out.fd.sync()
            }
        }
    }

    /** The most recent [count] readings, oldest first. */
    fun recent(context: Context, count: Int): List<Sample> = runCatching {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return emptyList()
        file.readLines().takeLast(count).mapNotNull { Sample.decode(it) }
    }.getOrDefault(emptyList())

    /**
     * Readings from the window before [before], newest last.
     *
     * Used after a freeze, where what matters is the state on the way down
     * rather than anything recorded since the restart.
     */
    fun leadingUpTo(context: Context, before: Long, count: Int): List<Sample> =
        recent(context, 4000).filter { it.at <= before }.takeLast(count)

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE).delete() }
    }

    /** Drops the oldest half rather than truncating, so a freeze mid-roll still leaves history. */
    private fun roll(file: File) {
        runCatching {
            val kept = file.readBytes().let { it.copyOfRange((it.size - KEEP_BYTES).coerceAtLeast(0), it.size) }
            val text = String(kept).substringAfter('\n')
            file.writeText(text)
        }
    }

    private fun readUptimeSec(): Long = runCatching {
        File("/proc/uptime").readText().substringBefore(' ').toDouble().toLong()
    }.getOrDefault(0L)

    private fun readMemAvailableMb(): Int = runCatching {
        File("/proc/meminfo").readLines()
            .first { it.startsWith("MemAvailable") }
            .filter { it.isDigit() }
            .toInt() / 1024
    }.getOrDefault(0)

    private fun readLoad1(): String = runCatching {
        File("/proc/loadavg").readText().substringBefore(' ')
    }.getOrDefault("")

    /**
     * What the Wi-Fi interface itself looks like to the kernel.
     *
     * Worth recording separately from anything WifiManager reports: a wedged
     * driver takes the interface out of the kernel entirely, and the framework
     * can still be claiming an association while `wlan0` no longer exists. An
     * empty result therefore means the radio is gone, not merely disconnected.
     */
    private fun readIfaceState(): String = runCatching {
        val dir = File("/sys/class/net/wlan0")
        if (!dir.exists()) return ""
        val state = runCatching { File(dir, "operstate").readText().trim() }.getOrDefault("?")
        val carrier = runCatching { File(dir, "carrier").readText().trim() }.getOrDefault("?")
        if (carrier == "1") state else "$state/nocarrier"
    }.getOrDefault("")

    /** Total received bytes, so a link that is associated but passing nothing shows up. */
    private fun readRxBytes(): Long = runCatching {
        File("/sys/class/net/wlan0/statistics/rx_bytes").readText().trim().toLong()
    }.getOrDefault(0L)

    /**
     * Supply rail in millivolts.
     *
     * These displays are mains powered and report a fixed-looking 3.9 V at
     * "level 50" on every unit, so this is very likely a placeholder rather than
     * a real gauge. It is recorded anyway because it costs one file read, and a
     * value that ever moves would point straight at power delivery - the one
     * remaining explanation for a freeze that leaves nothing behind.
     */
    private fun readMilliVolts(): Int = runCatching {
        val raw = File("/sys/class/power_supply/battery/voltage_now").readText().trim().toLong()
        // Reported in microvolts on this platform, millivolts on others.
        (if (raw > 100_000) raw / 1000 else raw).toInt()
    }.getOrDefault(0)

    /**
     * State and crash count of the Wi-Fi subsystem, as the kernel sees it.
     *
     * Wi-Fi runs as firmware on its own subsystem, and when that firmware
     * asserts the kernel restarts it and increments a counter. A counter that
     * climbs in the minutes before a display stops is the difference between
     * "the radio died and took the device with it" and "something else did" -
     * which is otherwise unanswerable, because a hang leaves no kernel log on
     * hardware without a persistent crash buffer.
     *
     * World-readable on this platform despite documentation suggesting
     * otherwise, so it is worth sampling.
     */
    private fun readWlanSubsys(): Pair<String, Int>? = runCatching {
        File("/sys/bus/msm_subsys").resolve("devices").listFiles()
            ?.firstOrNull { dev ->
                runCatching { File(dev, "name").readText().trim() }
                    .getOrDefault("")
                    .let { it.startsWith("AR6") || it.contains("wcnss", ignoreCase = true) }
            }
            ?.let { dev ->
                val state = runCatching { File(dev, "state").readText().trim() }.getOrDefault("")
                val crashes = runCatching {
                    File(dev, "crash_count").readText().trim().toInt()
                }.getOrDefault(0)
                state to crashes
            }
    }.getOrNull()

    /**
     * The hottest SoC sensor, in Celsius.
     *
     * Only the `tsens` zones are consulted. They are the dies that matter, and
     * the alternative - taking the maximum across every zone - reports whichever
     * sensor happens to use the largest units. On this hardware that is
     * `pa_therm0`, a power-amplifier thermistor reading a bare 83 on a device
     * with no cellular radio, which made an idle display running at 35 degrees
     * look like it was cooking.
     *
     * Zones report in millidegrees or tenths depending on the sensor, so the
     * value is scaled by magnitude and anything implausible is dropped rather
     * than guessed at.
     */
    private fun readMaxTempC(): String = runCatching {
        val zones = File("/sys/class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            .orEmpty()
        val soc = zones.filter { zone ->
            runCatching { File(zone, "type").readText().trim() }.getOrDefault("").startsWith("tsens")
        }
        (soc.ifEmpty { zones })
            .mapNotNull { zone ->
                val raw = runCatching { File(zone, "temp").readText().trim().toLong() }.getOrNull()
                when {
                    raw == null -> null
                    raw > 10_000 -> raw / 1000.0
                    raw > 100 -> raw / 10.0
                    else -> raw.toDouble()
                }
            }
            .filter { it in 0.0..120.0 }
            .maxOrNull()
            ?.let { String.format("%.0f", it) }
            .orEmpty()
    }.getOrDefault("")

    /**
     * Free space on the data partition, in megabytes.
     *
     * A kiosk that fills its storage stops being able to log, cache, or update,
     * and the failure looks like anything but a disk problem, so it is worth the
     * one line it costs to notice.
     */
    private fun readDataFreeMb(): Int = runCatching {
        val stat = android.os.StatFs(Environment.getDataDirectory().path)
        (stat.availableBytes / (1024 * 1024)).toInt()
    }.getOrDefault(-1)
}
