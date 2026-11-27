package uk.neatlines.stompbox.input

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import uk.neatlines.stompbox.debug.GapCounter
import uk.neatlines.stompbox.wit.Wit
import uk.neatlines.stompbox.wit.WitPacketParser
import uk.neatlines.stompbox.wit.WitSample

/**
 * Reads the WT901 over its USB-C cable. The sensor has a CH340 USB-serial chip at 115200 baud and
 * sends one `55 61` frame per sample (every 5 ms at 200 Hz), with none of the ~44 ms packing that
 * Bluetooth adds. It takes the same commands as over Bluetooth.
 *
 * Needs a phone that can act as USB host (OTG); most can. The CH340 is driven directly through
 * Android's USB host API (a handful of vendor control requests, as Linux's ch341 driver does), so
 * there is no driver library. One thread at audio priority does nothing but read, timestamp and
 * parse; setup, commands and polls run on a second thread.
 */
class WitUsbSource(
    private val context: Context,
    rateHz: Int = 200,
    private val bandwidthHz: Int = 188,
    /** Called on the USB read thread. Keep it short: it is on the latency path. */
    private val onSample: (WitSample, arrivalNanos: Long) -> Unit,
) : SensorSource {
    override val kind = "USB cable"
    @Volatile override var rateHz: Int = rateHz
        set(v) { require(v in Wit.RATE_CODES); field = v; handler.post { configureIfNeeded() } }
    @Volatile override var status: String = "idle"; private set
    @Volatile override var deviceName: String? = null; private set
    /** Over USB this register reads the supply (about 5 V) rather than the battery. */
    @Volatile override var batteryVolts: Double = Double.NaN; private set
    @Volatile override var rateRegister: Int = -1; private set
    @Volatile override var bandwidthRegister: Int = -1; private set
    @Volatile override var reconnects = 0; private set
    /** Gaps between USB reads that returned data (normally ~5 ms at 200 Hz). */
    override val gaps = GapCounter(thresholdMs = 250.0)
    @Volatile override var failedWrites = 0; private set
    /** USB reads that returned data, and the bytes in them: bytes per read shows how the chip packs frames. */
    @Volatile var reads = 0L; private set
    @Volatile var bytes = 0L; private set
    val framesPerRead: Double get() = if (reads == 0L) 0.0 else bytes / 20.0 / reads

    override val pingSummary: String get() {
        val v = synchronized(pings) { pings.sorted() }
        if (v.isEmpty()) return "no replies yet"
        return "median %.1f ms, min %.1f, max %.1f (n=%d)".format(v[v.size / 2], v.first(), v.last(), v.size)
    }
    private val pings = ArrayDeque<Double>()
    @Volatile private var pingSentAt = 0L

    override val running get() = !stopped
    @Volatile private var stopped = true

    private val usb = context.getSystemService(UsbManager::class.java)
    private val worker = HandlerThread("wit-usb").apply { start() }
    private val handler = Handler(worker.looper)
    /** The open port; only the worker thread changes it. The reader exits when it changes. */
    @Volatile private var port: Ch340? = null
    private var reader: Thread? = null
    private var everOpened = false
    private var receiverRegistered = false

    @Volatile private var arrival = 0L
    /** Samples before this time are the chip's start-up backlog and are dropped. */
    @Volatile private var streamFrom = 0L
    private val parser = WitPacketParser(
        onSample = { s -> if (arrival >= streamFrom) onSample(s, arrival) },
        onRegisters = { reg, v -> onRegisters(reg, v) },
    )

    override fun start() {
        if (!stopped) return
        stopped = false; reconnects = 0; everOpened = false; gaps.reset(); failedWrites = 0
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        handler.post(::tryOpen)
    }

    override fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        handler.post { close(); setStatus("idle") }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (i.action) {
                ACTION_PERMISSION -> handler.post {
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) tryOpen()
                    else if (!stopped) setStatus("USB permission refused: tap Connect to ask again")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handler.post(::tryOpen)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handler.post {
                    if (dev != null && dev.deviceName == port?.device?.deviceName) {
                        close()
                        if (!stopped) setStatus("unplugged: plug the sensor back in")
                    }
                }
            }
        }
    }

    // ---- worker thread ------------------------------------------------------------------------

    private fun tryOpen() {
        if (stopped || port != null) return
        val all = usb.deviceList.values
        val dev = all.firstOrNull(Ch340::supports)
        if (dev == null) {
            val other = all.firstOrNull()
            setStatus(if (other == null) "plug in the sensor (USB-C cable, or an OTG adapter)"
                else "USB device %04x:%04x isn't a CH340 (the WT901's chip)".format(other.vendorId, other.productId))
            return
        }
        if (!usb.hasPermission(dev)) {
            setStatus("waiting for USB permission")
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(dev, PendingIntent.getBroadcast(context, 0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName), flags))
            return
        }
        val conn = usb.openDevice(dev) ?: run { setStatus("couldn't open the USB device"); return }
        val p = runCatching { Ch340.open(dev, conn, BAUD) }.getOrElse {
            Log.w(TAG, "CH340 setup failed", it); conn.close(); setStatus("USB setup failed: ${it.message}"); return
        }
        if (everOpened) reconnects++
        everOpened = true
        deviceName = "WT901 via ${dev.productName ?: "USB serial"} (%04x:%04x)".format(dev.vendorId, dev.productId)
        rateRegister = -1; bandwidthRegister = -1; batteryVolts = Double.NaN
        reads = 0; bytes = 0; gaps.restart()
        streamFrom = System.nanoTime() + BACKLOG_MS * 1_000_000
        port = p
        reader = Thread({ readLoop(p) }, "wit-usb-read").apply { start() }
        setStatus("streaming")
        onOpened?.invoke()
        handler.postDelayed({ send(Wit.readRegister(Wit.REG_RATE)) }, BACKLOG_MS)
        handler.postDelayed({ send(Wit.readRegister(Wit.REG_BANDWIDTH)) }, BACKLOG_MS + 50)
        handler.postDelayed(::configureIfNeeded, BACKLOG_MS + 300)
        handler.postDelayed(poll, BACKLOG_MS + 500)
    }

    /** Called on the worker thread each time the port opens, so the engine can restart its clock. */
    var onOpened: (() -> Unit)? = null

    private fun close() {
        val p = port ?: return
        port = null
        reader?.join(500); reader = null
        p.close()
    }

    private fun send(cmd: ByteArray, isPing: Boolean = false) {
        val p = port ?: return
        if (isPing) pingSentAt = System.nanoTime()
        if (!p.write(cmd)) failedWrites++
    }

    private fun configureIfNeeded() {
        if (port == null || rateRegister < 0 || bandwidthRegister < 0) return
        val wantRate = Wit.RATE_CODES.getValue(rateHz)
        val wantBw = Wit.BANDWIDTH_CODES.getValue(bandwidthHz)
        if (rateRegister == wantRate && bandwidthRegister == wantBw) return
        Log.i(TAG, "configuring: rate reg $rateRegister→$wantRate, bandwidth reg $bandwidthRegister→$wantBw")
        // ~100 ms apart, as the WitMotion SDKs do; then read both back.
        Wit.configure(rateHz, bandwidthHz).forEachIndexed { k, cmd -> handler.postDelayed({ send(cmd) }, 100L * k) }
        val n = Wit.configure(rateHz, bandwidthHz).size
        handler.postDelayed({ send(Wit.readRegister(Wit.REG_RATE)) }, 100L * n + 200)
        handler.postDelayed({ send(Wit.readRegister(Wit.REG_BANDWIDTH)) }, 100L * n + 250)
    }

    /** Every 5 s: time a register read; every 10 s: read the supply/battery register. */
    private val poll = object : Runnable {
        private var k = 0
        override fun run() {
            if (port == null) return
            send(Wit.readRegister(Wit.REG_TEMP), isPing = true)
            if (k++ % 2 == 0) handler.postDelayed({ send(Wit.readRegister(Wit.REG_BATTERY)) }, 100)
            handler.postDelayed(this, 5000)
        }
    }

    // ---- read thread --------------------------------------------------------------------------

    private fun readLoop(p: Ch340) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(256)
        while (port === p) {
            val t0 = System.nanoTime()
            val n = p.read(buf, 200)
            if (n > 0) {
                arrival = System.nanoTime()
                gaps.mark(arrival)
                reads++; bytes += n
                parser.feed(buf, 0, n)
            } else if (n < 0 && System.nanoTime() - t0 < 5_000_000) {
                // Fails at once rather than timing out: usually unplugged, before the broadcast comes.
                Thread.sleep(20)
            }
        }
    }

    private fun onRegisters(reg: Int, v: ShortArray) {
        when (reg) {
            Wit.REG_TEMP -> {
                val sent = pingSentAt
                if (sent != 0L) {
                    pingSentAt = 0L
                    synchronized(pings) { pings.addLast((arrival - sent) / 1e6); while (pings.size > 30) pings.removeFirst() }
                }
            }
            Wit.REG_RATE -> rateRegister = v[0].toInt()
            Wit.REG_BANDWIDTH -> bandwidthRegister = v[0].toInt()
            Wit.REG_BATTERY -> batteryVolts = v[0] / 100.0
        }
    }

    private fun setStatus(s: String) { status = s; Log.i(TAG, s) }

    /**
     * The WCH CH340/CH341 USB-serial chip: claim its interface, set 115200 8N1 and raise DTR/RTS
     * (as a desktop serial port does on open). The control requests follow Linux's ch341.c.
     */
    private class Ch340(
        val device: UsbDevice,
        private val conn: UsbDeviceConnection,
        private val intf: UsbInterface,
        private val inEp: UsbEndpoint,
        private val outEp: UsbEndpoint,
    ) {
        /** Bytes read, 0 or -1 on timeout, -1 on error. */
        fun read(buf: ByteArray, timeoutMs: Int): Int = conn.bulkTransfer(inEp, buf, buf.size, timeoutMs)
        fun write(b: ByteArray): Boolean = conn.bulkTransfer(outEp, b, b.size, 200) == b.size
        fun close() { runCatching { conn.releaseInterface(intf) }; conn.close() }

        companion object {
            private val IDS = setOf(0x1A86 to 0x7523, 0x1A86 to 0x5523, 0x1A86 to 0x7522)
            fun supports(d: UsbDevice) = (d.vendorId to d.productId) in IDS

            fun open(d: UsbDevice, conn: UsbDeviceConnection, baud: Int): Ch340 {
                val intf = d.getInterface(0)
                check(conn.claimInterface(intf, true)) { "couldn't claim the interface" }
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (i in 0 until intf.endpointCount) {
                    val e = intf.getEndpoint(i)
                    if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
                }
                checkNotNull(inEp) { "no bulk IN endpoint" }; checkNotNull(outEp) { "no bulk OUT endpoint" }

                fun out(req: Int, value: Int, index: Int) =
                    check(conn.controlTransfer(0x40, req, value, index, null, 0, 1000) >= 0) { "request 0x%02x failed".format(req) }
                fun inp(req: Int, value: Int) {
                    val b = ByteArray(2)
                    check(conn.controlTransfer(0xC0, req, value, 0, b, b.size, 1000) >= 0) { "request 0x%02x failed".format(req) }
                }
                fun setBaud() {
                    // Clock 12 MHz: divisor = 1532620800 / baud, prescaler 3; bit 7 = send at once.
                    var factor = 1_532_620_800L / baud
                    var div = 3L
                    while (factor > 0xFFF0 && div > 0) { factor = factor shr 3; div-- }
                    check(factor <= 0xFFF0) { "unsupported baud $baud" }
                    factor = 0x10000 - factor
                    out(0x9A, 0x1312, ((factor and 0xFF00) or div or 0x80).toInt())   // 115200 → 0xCC83
                    out(0x9A, 0x0F2C, (factor and 0xFF).toInt())                     // 115200 → 0x08
                }

                inp(0x5F, 0)                          // read version
                out(0xA1, 0, 0)                       // serial init
                setBaud()
                inp(0x95, 0x2518)
                out(0x9A, 0x2518, 0xC3)               // LCR: enable RX and TX, 8 data bits, no parity, 1 stop
                inp(0x95, 0x0706)
                out(0xA1, 0x501F, 0xD90A)
                setBaud()
                out(0xA4, 0xFF9F, 0)                  // modem control: DTR and RTS on (active low)
                inp(0x95, 0x0706)
                return Ch340(d, conn, intf, inEp, outEp)
            }
        }
    }

    private companion object {
        const val TAG = "WitUsb"
        const val BAUD = 115_200
        const val BACKLOG_MS = 300L
        const val ACTION_PERMISSION = "uk.neatlines.stompbox.USB_PERMISSION"
    }
}
