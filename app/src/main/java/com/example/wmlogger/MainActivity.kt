package com.example.wmlogger

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.graphics.Typeface
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {

    // دستورات فقط‌خواندنی API ماینر (پورت 4028) — بسته به فریمور ممکن است بعضی پشتیبانی نشوند
    private val commands = listOf(
        "summary", "status", "pools", "devs",
        "get_version", "get_miner_info", "get_error_code"
    )
    // پورت‌هایی که برای پیدا کردن دستگاه‌های زنده امتحان می‌شود
    private val probePorts = intArrayOf(80, 4028, 443, 22, 8080, 445, 53, 62078)

    private lateinit var base: EditText
    private lateinit var from: EditText
    private lateinit var to: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var logPath: EditText

    private lateinit var minerBtn: Button
    private lateinit var minerSaveAs: Button
    private lateinit var minerShare: Button
    private lateinit var minerOut: TextView
    private lateinit var devBtn: Button
    private lateinit var devSaveAs: Button
    private lateinit var devShare: Button
    private lateinit var devOut: TextView

    private var minerText = ""
    private var minerUri: Uri? = null
    private var devText = ""
    private var devUri: Uri? = null
    private var pendingSave: String? = null

    private val REQ_SAVE = 42

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        // بخش مشترک: محدوده شبکه
        base = edit(root, "Subnet base (e.g. 192.168.1)", "192.168.1")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        from = edit(row, "From", "1", true)
        to = edit(row, "To", "254", true)
        root.addView(row)
        root.addView(button("Auto-detect subnet") {
            bindEthernet()
            val b = detectBase()
            if (b != null) base.setText(b) else toast("Network not detected")
        })
        detectBase()?.let { base.setText(it) }

        // تب‌ها
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val panelMiners = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val panelDevices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun show(i: Int) {
            panelMiners.visibility = if (i == 0) View.VISIBLE else View.GONE
            panelDevices.visibility = if (i == 1) View.VISIBLE else View.GONE
        }
        tabs.addView(button("Miner logs") { show(0) }, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(button("All devices") { show(1) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)
        root.addView(panelMiners, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(panelDevices, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- تب ماینرها
        user = edit(panelMiners, "Web user (optional)", "admin")
        pass = edit(panelMiners, "Web password (optional)", "admin")
        logPath = edit(panelMiners, "Web log path (optional, e.g. /cgi-bin/luci/...)")
        minerBtn = button("Scan & collect miner logs") { startMinerScan() }
        panelMiners.addView(minerBtn)
        val mRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        minerSaveAs = button("Save as…") { saveAs(minerText, "whatsminer_logs.txt") }.apply { isEnabled = false }
        minerShare = button("Share") { share(minerUri) }.apply { isEnabled = false }
        mRow.addView(minerSaveAs, LinearLayout.LayoutParams(0, -2, 1f))
        mRow.addView(minerShare, LinearLayout.LayoutParams(0, -2, 1f))
        panelMiners.addView(mRow)
        minerOut = output()
        panelMiners.addView(ScrollView(this).apply { addView(minerOut) }, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- تب همه دستگاه‌ها
        devBtn = button("Scan all devices on network") { startDeviceScan() }
        panelDevices.addView(devBtn)
        val dRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        devSaveAs = button("Save as…") { saveAs(devText, "network_devices.txt") }.apply { isEnabled = false }
        devShare = button("Share") { share(devUri) }.apply { isEnabled = false }
        dRow.addView(devSaveAs, LinearLayout.LayoutParams(0, -2, 1f))
        dRow.addView(devShare, LinearLayout.LayoutParams(0, -2, 1f))
        panelDevices.addView(dRow)
        devOut = output()
        panelDevices.addView(ScrollView(this).apply { addView(devOut) }, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun edit(parent: LinearLayout, hint: String, text: String = "", number: Boolean = false) =
        EditText(this).apply {
            this.hint = hint
            setText(text)
            setSingleLine()
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            parent.addView(this, if (parent.orientation == LinearLayout.HORIZONTAL)
                LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } }

    private fun output() = TextView(this).apply {
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun minerLog(s: String) = runOnUiThread { minerOut.append(s + "\n") }
    private fun devLog(s: String) = runOnUiThread { devOut.append(s + "\n") }

    // ---------------------------------------------------------------- ذخیره و اشتراک‌گذاری

    /** ذخیره‌ی خودکار در Downloads/WhatsMinerLogs (بدون permission در اندروید 10+) */
    private fun save(prefix: String, text: String): Pair<String, Uri> {
        val name = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WhatsMinerLogs")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        return name to uri
    }

    /** انتخاب محل ذخیره توسط کاربر (فایل‌منیجر) */
    private fun saveAs(text: String, name: String) {
        if (text.isEmpty()) { toast("Nothing to save yet"); return }
        pendingSave = text
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(i, REQ_SAVE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SAVE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)!!.use { it.write((pendingSave ?: "").toByteArray()) }
                toast("Saved")
            } catch (e: Exception) { toast("Save failed: ${e.message}") }
        }
    }

    private fun share(uri: Uri?) {
        if (uri == null) { toast("Nothing to share yet"); return }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share"))
    }

    // ---------------------------------------------------------------- شبکه

    /** اگر کابل شبکه وصل است، ترافیک برنامه را روی Ethernet ببند (وگرنه اندروید به دیتای موبایل می‌رود) */
    @Suppress("DEPRECATION")
    private fun bindEthernet(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val eth = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
        cm.bindProcessToNetwork(eth)
        return eth != null
    }

    /** ساب‌نت فعلی را از IP خود گوشی حدس می‌زند (فرض /24) */
    @Suppress("DEPRECATION")
    private fun detectBase(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val nets = cm.allNetworks.sortedByDescending {
            if (cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) 1 else 0
        }
        for (n in nets) {
            if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) continue
            val a = cm.getLinkProperties(n)?.linkAddresses?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            if (a != null) return a.hostAddress?.substringBeforeLast('.')
        }
        return null
    }

    /** IPهای خود گوشی و آدرس گیت‌وی (مودم/روتر) */
    @Suppress("DEPRECATION")
    private fun ownAndGateways(): Pair<Set<String>, Set<String>> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val own = mutableSetOf<String>()
        val gws = mutableSetOf<String>()
        for (n in cm.allNetworks) {
            if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) continue
            val lp = cm.getLinkProperties(n) ?: continue
            lp.linkAddresses.forEach { (it.address as? Inet4Address)?.hostAddress?.let(own::add) }
            lp.routes.filter { it.isDefaultRoute }.forEach { (it.gateway as? Inet4Address)?.hostAddress?.let(gws::add) }
        }
        return own to gws
    }

    // ---------------------------------------------------------------- تب ۱: لاگ ماینرها

    private fun startMinerScan() {
        val b = base.text.toString().trim()
        val f = from.text.toString().toIntOrNull() ?: 1
        val t = to.text.toString().toIntOrNull() ?: 254
        val u = user.text.toString()
        val p = pass.text.toString()
        val path = logPath.text.toString().trim()
        minerBtn.isEnabled = false
        minerSaveAs.isEnabled = false
        minerShare.isEnabled = false
        minerOut.text = ""
        Thread {
            try { scanMiners(b, f, t, u, p, path) }
            catch (e: Exception) { minerLog("Error: ${e.message}") }
            runOnUiThread { minerBtn.isEnabled = true }
        }.start()
    }

    private fun scanMiners(b: String, f: Int, t: Int, u: String, p: String, path: String) {
        minerLog(if (bindEthernet()) "Using Ethernet network" else "Ethernet not found, using default network")
        minerLog("Scanning $b.$f - $b.$t ...")

        val results = ConcurrentSkipListMap<Int, String>()
        val pool = Executors.newFixedThreadPool(48)
        val latch = CountDownLatch(t - f + 1)

        for (i in f..t) {
            pool.execute {
                try {
                    val ip = "$b.$i"
                    val first = api(ip, "summary") ?: return@execute   // جواب نداد = ماینر نیست
                    val sb = StringBuilder("===== $ip =====\n--- summary ---\n$first\n")
                    for (c in commands.drop(1)) {
                        sb.append("--- $c ---\n").append(api(ip, c) ?: "(no response)").append("\n")
                    }
                    if (path.isNotEmpty()) {
                        sb.append("--- web log ($path) ---\n")
                            .append(webLog(ip, u, p, path) ?: "(login failed)").append("\n")
                    }
                    results[i] = sb.toString()
                    minerLog("Found miner: $ip")
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        pool.shutdown()

        if (results.isEmpty()) { minerLog("No miners found."); return }
        val text = results.values.joinToString("\n")
        val (name, uri) = save("whatsminer_logs", text)
        minerText = text
        minerUri = uri
        minerLog("Done. ${results.size} miner(s). Saved: Downloads/WhatsMinerLogs/$name")
        runOnUiThread { minerSaveAs.isEnabled = true; minerShare.isEnabled = true }
    }

    /** ارسال یک دستور JSON به API ماینر (TCP 4028) و خواندن جواب تا بسته شدن اتصال */
    private fun api(ip: String, cmd: String): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, 4028), 800)
            s.soTimeout = 3000
            s.getOutputStream().apply { write("""{"cmd":"$cmd"}""".toByteArray()); flush() }
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            val ins = s.getInputStream()
            try {
                while (true) {
                    val n = ins.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
            } catch (_: SocketTimeoutException) { }
            val text = buf.toString("UTF-8").trim { it <= ' ' }
            if (text.isEmpty()) null else runCatching { JSONObject(text).toString(2) }.getOrDefault(text)
        }
    } catch (e: Exception) { null }

    /** لاگین به پنل وب (LuCI) و گرفتن یک صفحه لاگ. مسیر صفحه‌ی لاگ را باید خودتان بدهید. */
    private fun webLog(ip: String, user: String, pass: String, path: String): String? = try {
        val login = URL("http://$ip/cgi-bin/luci").openConnection() as HttpURLConnection
        login.instanceFollowRedirects = false
        login.requestMethod = "POST"
        login.doOutput = true
        login.connectTimeout = 3000
        login.readTimeout = 5000
        val body = "luci_username=${URLEncoder.encode(user, "UTF-8")}" +
                "&luci_password=${URLEncoder.encode(pass, "UTF-8")}"
        login.outputStream.use { it.write(body.toByteArray()) }
        val cookie = login.headerFields["Set-Cookie"]
            ?.firstOrNull { it.contains("sysauth") }?.substringBefore(";")
        login.disconnect()
        if (cookie == null) null else {
            val c = URL("http://$ip$path").openConnection() as HttpURLConnection
            c.setRequestProperty("Cookie", cookie)
            c.connectTimeout = 3000
            c.readTimeout = 10000
            c.inputStream.bufferedReader().use { it.readText() }
        }
    } catch (e: Exception) { "web log error: ${e.message}" }

    // ---------------------------------------------------------------- تب ۲: همه دستگاه‌ها

    private fun startDeviceScan() {
        val b = base.text.toString().trim()
        val f = from.text.toString().toIntOrNull() ?: 1
        val t = to.text.toString().toIntOrNull() ?: 254
        devBtn.isEnabled = false
        devSaveAs.isEnabled = false
        devShare.isEnabled = false
        devOut.text = ""
        Thread {
            try { scanDevices(b, f, t) }
            catch (e: Exception) { devLog("Error: ${e.message}") }
            runOnUiThread { devBtn.isEnabled = true }
        }.start()
    }

    private fun scanDevices(b: String, f: Int, t: Int) {
        devLog(if (bindEthernet()) "Using Ethernet network" else "Ethernet not found, using default network")
        devLog("Scanning $b.$f - $b.$t (takes ~10-20 s) ...")
        val (own, gws) = ownAndGateways()

        val found = ConcurrentSkipListMap<Int, String>()
        val miners = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(64)
        val latch = CountDownLatch(t - f + 1)

        for (i in f..t) {
            pool.execute {
                try {
                    val ip = "$b.$i"
                    val (alive, open) = probe(ip)
                    if (!alive) return@execute

                    var label = when {
                        ip in gws -> "ROUTER"
                        ip in own -> "THIS-PHONE"
                        else -> "DEVICE"
                    }
                    if (4028 in open) {
                        val v = api(ip, "get_version")
                        label = if (v?.contains("whatsminer", ignoreCase = true) == true) "WHATSMINER" else "MINER?"
                        miners.incrementAndGet()
                    }
                    val host = hostName(ip)
                    found[i] = "%-15s %-11s %-22s ports: %s".format(
                        ip, label, host.ifEmpty { "-" }, if (open.isEmpty()) "-" else open.joinToString(",")
                    )
                    devLog("+ $ip  $label")
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        pool.shutdown()

        if (found.isEmpty()) { devLog("No devices found."); return }
        val header = "Devices on $b.x — ${found.size} found, ${miners.get()} miner(s)\n" +
                "(MAC addresses are not readable on Android 10+; devices that ignore all probes may not show up)\n\n"
        val text = header + found.values.joinToString("\n") + "\n"
        val (name, uri) = save("network_devices", text)
        devText = text
        devUri = uri
        runOnUiThread {
            devOut.text = text + "\nSaved: Downloads/WhatsMinerLogs/$name"
            devSaveAs.isEnabled = true
            devShare.isEnabled = true
        }
    }

    /** دستگاه زنده است اگر پورتی باز باشد، یا «connection refused» بدهد (یعنی میزبان جواب می‌دهد)، یا ping شود */
    private fun probe(ip: String): Pair<Boolean, List<Int>> {
        var alive = false
        val open = mutableListOf<Int>()
        for (p in probePorts) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, p), 300) }
                alive = true
                open += p
            } catch (e: ConnectException) {
                val m = e.message ?: ""
                if (m.contains("ECONNREFUSED") || m.contains("refused", ignoreCase = true)) alive = true
            } catch (_: Exception) { }
        }
        if (!alive) alive = try { InetAddress.getByName(ip).isReachable(400) } catch (_: Exception) { false }
        return alive to open
    }

    /** نام میزبان از DNS مودم (اگر جواب ندهد خالی برمی‌گردد) */
    private fun hostName(ip: String): String {
        var r = ""
        val th = Thread { r = try { InetAddress.getByName(ip).canonicalHostName } catch (_: Exception) { "" } }
        th.start()
        th.join(1200)
        return if (r == ip) "" else r
    }
}
