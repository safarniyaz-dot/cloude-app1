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
import android.util.Base64
import android.graphics.Typeface
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {

    // دستورات فقط‌خواندنی API ماینر (پورت 4028) — بسته به فریمور ممکن است بعضی پشتیبانی نشوند
    private val commands = listOf(
        "summary", "status", "pools", "edevs", "devdetails", "get_psu",
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
    private lateinit var keywords: EditText
    private lateinit var fullLogs: CheckBox

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
        pass = edit(panelMiners, "Admin password (web + API)", "admin")
        logPath = edit(panelMiners, "Web log path (optional, e.g. /cgi-bin/luci/...)")
        keywords = edit(panelMiners, "Filter keywords, comma separated (pool change lines)", "pool")
        fullLogs = CheckBox(this).apply {
            text = "Download full log package (Miner.log, api.log, system.log...)"
            isChecked = true
            panelMiners.addView(this)
        }
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
        val full = fullLogs.isChecked
        val kw = keywords.text.toString().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        minerBtn.isEnabled = false
        minerSaveAs.isEnabled = false
        minerShare.isEnabled = false
        minerOut.text = ""
        Thread {
            try { scanMiners(b, f, t, u, p, path, full, kw) }
            catch (e: Exception) { minerLog("Error: ${e.message}") }
            runOnUiThread { minerBtn.isEnabled = true }
        }.start()
    }

    private fun scanMiners(b: String, f: Int, t: Int, u: String, p: String, path: String,
                           full: Boolean, kw: List<String>) {
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
                    if (full) {
                        if (p.isEmpty()) {
                            sb.append("--- full log package ---\n(skipped: admin password is empty)\n")
                        } else {
                            val diag = StringBuilder()
                            val (pkg, note) = downloadLogs(ip, p, diag)
                            if (pkg != null) {
                                val (ext, entries) = unpackLogs(pkg)
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                val mime = if (ext == "zip") "application/zip" else "application/octet-stream"
                                try { saveBytes("whatsminer_${ip}_logs_$stamp.$ext", mime, pkg) } catch (e: Exception) { }
                                sb.append("--- full log package ($ext, ${pkg.size} bytes, ${entries.size} file(s)) ---\n")
                                    .append(summarizeLogs(entries, kw)).append("\n")
                                minerLog("  $ip: log package received ($ext, ${pkg.size} bytes)")
                            } else {
                                sb.append("--- full log package ---\n(not downloaded: $note)\n--- attempts tried ---\n").append(diag)
                                minerLog("  $ip: log package failed: $note (see file for attempt details)")
                            }
                        }
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
            } catch (e: SocketTimeoutException) { }
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

    // ---------------------------------------------------------------- دانلود بسته‌ی لاگ کامل (API نوشتنی + رمزنگاری)
    // طبق مستند Whatsminer API v2: get_token -> key/sign با md5-crypt -> AES-256-ECB -> download_logs

    private class Entry(val name: String, val data: ByteArray)
    private class TokenInfo(val time: String, val salt: String, val newsalt: String)

    private val ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private fun ub(b: Byte): Int = b.toInt() and 0xff

    private fun to64(sb: StringBuilder, v: Int, n: Int) {
        var x = v
        repeat(n) { sb.append(ITOA64[x and 0x3f]); x = x ushr 6 }
    }

    /** md5-crypt ($1$) — معادل `openssl passwd -1 -salt SALT PASS`. با fullOut=true رشته‌ی کامل $1$salt$hash برمی‌گردد، وگرنه فقط بخش hash. */
    private fun md5crypt(password: String, salt: String, fullOut: Boolean = false): String {
        val pw = password.toByteArray(Charsets.UTF_8)
        val sl = salt.take(8).toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("MD5")
        md.update(pw); md.update(sl); md.update(pw)
        var fin = md.digest()

        md.reset()
        md.update(pw); md.update("\$1\$".toByteArray()); md.update(sl)
        var pl = pw.size
        while (pl > 0) { md.update(fin, 0, minOf(16, pl)); pl -= 16 }
        var i = pw.size
        while (i != 0) {
            if ((i and 1) != 0) md.update(0.toByte()) else md.update(pw[0])
            i = i shr 1
        }
        fin = md.digest()

        for (r in 0 until 1000) {
            md.reset()
            if ((r and 1) != 0) md.update(pw) else md.update(fin)
            if (r % 3 != 0) md.update(sl)
            if (r % 7 != 0) md.update(pw)
            if ((r and 1) != 0) md.update(fin) else md.update(pw)
            fin = md.digest()
        }
        val sb = StringBuilder()
        to64(sb, (ub(fin[0]) shl 16) or (ub(fin[6]) shl 8) or ub(fin[12]), 4)
        to64(sb, (ub(fin[1]) shl 16) or (ub(fin[7]) shl 8) or ub(fin[13]), 4)
        to64(sb, (ub(fin[2]) shl 16) or (ub(fin[8]) shl 8) or ub(fin[14]), 4)
        to64(sb, (ub(fin[3]) shl 16) or (ub(fin[9]) shl 8) or ub(fin[15]), 4)
        to64(sb, (ub(fin[4]) shl 16) or (ub(fin[10]) shl 8) or ub(fin[5]), 4)
        to64(sb, ub(fin[11]), 2)
        val hash = sb.toString()
        return if (fullOut) "\$1\$" + salt.take(8) + "\$" + hash else hash
    }

    private fun getTokenInfo(ip: String): TokenInfo? {
        val text = api(ip, "get_token") ?: return null
        val j = try { JSONObject(text) } catch (e: Exception) { return null }
        val m = j.optJSONObject("Msg") ?: j
        val time = m.optString("time")
        val salt = m.optString("salt")
        val newsalt = m.optString("newsalt")
        if (time.isEmpty() || salt.isEmpty() || newsalt.isEmpty()) return null
        return TokenInfo(time, salt, newsalt)
    }

    private fun aesCrypt(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    private fun padZero(raw: ByteArray) = raw.copyOf(((raw.size + 15) / 16) * 16)
    private fun padPkcs7(raw: ByteArray): ByteArray {
        val padLen = 16 - (raw.size % 16)
        val out = raw.copyOf(raw.size + padLen)
        for (k in raw.size until out.size) out[k] = padLen.toByte()
        return out
    }

    /** اولین آبجکت JSON کامل داخل بافر؛ اندیس بعد از } یا -1 */
    private fun jsonEnd(b: ByteArray): Int {
        var depth = 0
        var inStr = false
        var esc = false
        var started = false
        for (i in b.indices) {
            val c = ub(b[i]).toChar()
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            if (c == '"') inStr = true
            else if (c == '{') { depth++; started = true }
            else if (c == '}') { depth--; if (started && depth == 0) return i + 1 }
        }
        return -1
    }

    private fun sendRaw(ip: String, bytes: ByteArray): Pair<ByteArray?, String> {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(ip, 4028), 3000)
            s.soTimeout = 20000
            val out = s.getOutputStream()
            out.write(bytes); out.flush()
            val ins = s.getInputStream()
            val chunk = ByteArray(65536)
            val buf = ByteArrayOutputStream()
            var end = -1
            while (end < 0 && buf.size() < 131072) {
                val n = try { ins.read(chunk) } catch (e: SocketTimeoutException) { -1 }
                if (n < 0) break
                buf.write(chunk, 0, n)
                end = jsonEnd(buf.toByteArray())
            }
            if (end < 0) return null to "no valid response from miner"
            val all = buf.toByteArray()
            return all to end.toString()
        } catch (e: Exception) {
            return null to ("error: " + e.message)
        } finally {
            try { s.close() } catch (e: Exception) { }
        }
    }

    /**
     * سند رسمی WhatsMiner دو ابهام دارد که خودش را نقض می‌کند: کلید AES از کل رشته‌ی md5crypt
     * ساخته می‌شود یا فقط بخش هش آن؟ و متن دستور رمزنشده JSON است یا با | جدا می‌شود؟ چون بدون
     * ماینر واقعی نمی‌شود مطمئن شد، برنامه چند حالت را امتحان می‌کند و هر کدام جواب داد همان می‌ماند.
     */
    private fun downloadLogs(ip: String, password: String, diag: StringBuilder): Pair<ByteArray?, String> {
        val ti = getTokenInfo(ip) ?: return null to "could not get a token from the miner"
        val hashKey = md5crypt(password, ti.salt, fullOut = false)
        val fullKey = md5crypt(password, ti.salt, fullOut = true)
        val signLast4 = md5crypt(hashKey + ti.time.takeLast(4), ti.newsalt, fullOut = false)
        val signFull = md5crypt(hashKey + ti.time, ti.newsalt, fullOut = false)

        class Combo(val label: String, val aesKey: ByteArray, val pad: (ByteArray) -> ByteArray, val sign: String, val plainFmt: Int)
        val combos = mutableListOf<Combo>()
        for (keyPair in listOf("hashKey" to hashKey, "fullKey" to fullKey)) {
            val aesKey = MessageDigest.getInstance("SHA-256").digest(keyPair.second.toByteArray(Charsets.UTF_8))
            for (padPair in listOf<Pair<String, (ByteArray) -> ByteArray>>("zeroPad" to ::padZero, "pkcs7" to ::padPkcs7)) {
                for (signPair in listOf("last4" to signLast4, "fullTime" to signFull)) {
                    for (fmt in 0..1) {
                        combos.add(Combo(keyPair.first + "+" + padPair.first + "+" + signPair.first + "+fmt" + fmt, aesKey, padPair.second, signPair.second, fmt))
                    }
                }
            }
        }

        fun plainFor(c: Combo): String = if (c.plainFmt == 0)
            "{\"cmd\":\"download_logs\",\"token\":\"" + c.sign + "\"}"
        else
            "token," + c.sign + "|download_logs"

        for (c in combos) {
            val raw = plainFor(c).toByteArray(Charsets.UTF_8)
            val encData = Base64.encodeToString(aesCrypt(Cipher.ENCRYPT_MODE, c.aesKey, c.pad(raw)), Base64.NO_WRAP)
            val envelope = ("{\"enc\":1,\"data\":\"" + encData + "\"}").toByteArray(Charsets.UTF_8)

            val (all, endStr) = sendRaw(ip, envelope)
            if (all == null) { diag.append(c.label + ": " + endStr + "\n"); continue }
            val end = endStr.toInt()
            val head = String(all, 0, end, Charsets.UTF_8)
            val headJson = try { JSONObject(head) } catch (e: Exception) { null }
            if (headJson == null) { diag.append(c.label + ": non-JSON response\n"); continue }

            val plainHead: String = if (headJson.has("enc") && headJson.has("data")) {
                try {
                    val encRaw = Base64.decode(headJson.getString("data"), Base64.DEFAULT)
                    val dec = aesCrypt(Cipher.DECRYPT_MODE, c.aesKey, encRaw.copyOf((encRaw.size / 16) * 16))
                    String(dec, Charsets.UTF_8).trimEnd('\u0000')
                } catch (e: Exception) { head }
            } else head

            val respJson = try { JSONObject(plainHead) } catch (e: Exception) { null }
            if (respJson == null) { diag.append(c.label + ": reply not valid JSON: " + plainHead.take(200) + "\n"); continue }

            val code = respJson.optInt("Code", -1)
            if (code == 23) { diag.append(c.label + ": Code 23 (miner could not decrypt/parse our command)\n"); continue }
            if (code == 135) { diag.append(c.label + ": Code 135 (token/sign rejected)\n"); continue }
            if (code == 45) { diag.append(c.label + ": Code 45 (permission denied — enable write API + change default password via WhatsMinerTool)\n"); continue }
            if (code != 131) { diag.append(c.label + ": " + plainHead.take(200) + "\n"); continue }

            val len = (respJson.opt("Msg") as? JSONObject)?.optString("logfilelen")?.toIntOrNull() ?: -1
            if (len <= 0) { diag.append(c.label + ": Code 131 but no logfilelen: " + plainHead.take(200) + "\n"); continue }

            diag.append(c.label + ": SUCCESS\n")
            val s2 = Socket()
            val body = ByteArrayOutputStream()
            try {
                s2.connect(InetSocketAddress(ip, 4028), 3000)
                s2.soTimeout = 20000
                val out2 = s2.getOutputStream()
                val raw2 = plainFor(c).toByteArray(Charsets.UTF_8)
                val encData2 = Base64.encodeToString(aesCrypt(Cipher.ENCRYPT_MODE, c.aesKey, c.pad(raw2)), Base64.NO_WRAP)
                out2.write(("{\"enc\":1,\"data\":\"" + encData2 + "\"}").toByteArray(Charsets.UTF_8)); out2.flush()
                val ins2 = s2.getInputStream()
                val chunk2 = ByteArray(65536)
                val buf2 = ByteArrayOutputStream()
                var end2 = -1
                while (end2 < 0) {
                    val n = ins2.read(chunk2); if (n < 0) break
                    buf2.write(chunk2, 0, n); end2 = jsonEnd(buf2.toByteArray())
                }
                if (end2 < 0) { diag.append("(retry) no header on binary fetch\n"); continue }
                val allBytes = buf2.toByteArray()
                body.write(allBytes, end2, allBytes.size - end2)
                while (body.size() < len) {
                    val n = try { ins2.read(chunk2) } catch (e: SocketTimeoutException) { -1 }
                    if (n < 0) break
                    body.write(chunk2, 0, n)
                }
            } finally {
                try { s2.close() } catch (e: Exception) { }
            }
            return body.toByteArray() to "ok"
        }
        return null to "all attempts failed; see diagnostic below"
    }

    // ---------------------------------------------------------------- باز کردن بسته‌ی لاگ (tar.gz / zip / tar / متن)

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var e = off
        while (e < off + len && b[e].toInt() != 0) e++
        return String(b, off, e - off, Charsets.UTF_8)
    }

    private fun isTar(b: ByteArray): Boolean =
        b.size > 512 && String(b, 257, 5, Charsets.US_ASCII) == "ustar"

    private fun readTar(b: ByteArray): List<Entry> {
        val res = mutableListOf<Entry>()
        var pos = 0
        while (pos + 512 <= b.size) {
            if (b[pos].toInt() == 0) break
            val name = cstr(b, pos, 100)
            val size = String(b, pos + 124, 12, Charsets.US_ASCII).trim { it <= ' ' }.toIntOrNull(8) ?: 0
            val type = ub(b[pos + 156]).toChar()
            val prefix = cstr(b, pos + 345, 155)
            pos += 512
            if (type == '0' || type == '\u0000') {
                val endPos = minOf(pos + size, b.size)
                val full = if (prefix.isNotEmpty()) prefix + "/" + name else name
                res.add(Entry(full, b.copyOfRange(pos, endPos)))
            }
            pos += ((size + 511) / 512) * 512
        }
        return res
    }

    private fun unpackLogs(bytes: ByteArray): Pair<String, List<Entry>> {
        return try {
            if (bytes.size > 2 && ub(bytes[0]) == 0x1f && ub(bytes[1]) == 0x8b) {
                val raw = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
                if (isTar(raw)) Pair("tar.gz", readTar(raw)) else Pair("gz", listOf(Entry("log", raw)))
            } else if (bytes.size > 4 && ub(bytes[0]) == 0x50 && ub(bytes[1]) == 0x4b) {
                val list = mutableListOf<Entry>()
                ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        if (!e.isDirectory) list.add(Entry(e.name, z.readBytes()))
                    }
                }
                Pair("zip", list)
            } else if (isTar(bytes)) {
                Pair("tar", readTar(bytes))
            } else {
                Pair("bin", listOf(Entry("log", bytes)))
            }
        } catch (e: Exception) {
            Pair("bin", listOf(Entry("log", bytes)))
        }
    }

    private fun looksText(d: ByteArray): Boolean {
        val n = minOf(d.size, 2000)
        var bad = 0
        for (k in 0 until n) {
            val c = ub(d[k])
            if (c < 9 || (c in 14..31)) bad++
        }
        return bad * 20 < maxOf(n, 1)
    }

    /** آخرین 300 خط هر فایل + همه‌ی خطوطی که کلمه‌ی کلیدی (مثلاً pool) دارند */
    private fun summarizeLogs(entries: List<Entry>, kw: List<String>): String {
        val sb = StringBuilder()
        val hits = StringBuilder()
        var hitCount = 0
        for (e in entries) {
            if (!looksText(e.data)) {
                sb.append("--- " + e.name + " (binary, " + e.data.size + " bytes) ---\n")
                continue
            }
            val lines = String(e.data, Charsets.UTF_8).lines()
            sb.append("--- " + e.name + " (" + lines.size + " lines, last 300 shown) ---\n")
            sb.append(lines.takeLast(300).joinToString("\n")).append("\n")
            if (kw.isNotEmpty()) {
                for (l in lines) {
                    if (hitCount >= 3000) break
                    val low = l.lowercase()
                    if (kw.any { low.contains(it) }) {
                        hits.append(e.name).append(": ").append(l).append("\n")
                        hitCount++
                    }
                }
            }
        }
        sb.append("--- lines matching " + kw + " (" + hitCount + ", all files) ---\n").append(hits)
        return sb.toString()
    }

    private fun saveBytes(name: String, mime: String, data: ByteArray): Uri {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WhatsMinerLogs")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        return uri
    }

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
            } catch (e: Exception) { }
        }
        if (!alive) alive = try { InetAddress.getByName(ip).isReachable(400) } catch (e: Exception) { false }
        return alive to open
    }

    /** نام میزبان از DNS مودم (اگر جواب ندهد خالی برمی‌گردد) */
    private fun hostName(ip: String): String {
        var r = ""
        val th = Thread { r = try { InetAddress.getByName(ip).canonicalHostName } catch (e: Exception) { "" } }
        th.start()
        th.join(1200)
        return if (r == ip) "" else r
    }
}
