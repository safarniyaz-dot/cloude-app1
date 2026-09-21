package com.example.wmlogger

import android.app.Activity
import android.content.ContentValues
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
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

class MainActivity : Activity() {

    // دستورات فقط‌خواندنی API ماینر (پورت 4028) — بسته به فریمور ممکن است بعضی پشتیبانی نشوند
    private val commands = listOf(
        "summary", "status", "pools", "devs",
        "get_version", "get_miner_info", "get_error_code"
    )

    private lateinit var base: EditText
    private lateinit var from: EditText
    private lateinit var to: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var logPath: EditText
    private lateinit var btn: Button
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun field(hint: String, text: String = "", number: Boolean = false) =
            EditText(this).apply {
                this.hint = hint
                setText(text)
                setSingleLine()
                if (number) inputType = InputType.TYPE_CLASS_NUMBER
                root.addView(this)
            }

        base = field("Subnet base (e.g. 192.168.1)", "192.168.1")
        from = field("From (last octet)", "1", true)
        to = field("To (last octet)", "254", true)
        user = field("Web user (optional)", "admin")
        pass = field("Web password (optional)", "admin")
        logPath = field("Web log path (optional, e.g. /cgi-bin/luci/...)")

        btn = Button(this).apply {
            text = "Scan & collect"
            setOnClickListener { start() }
            root.addView(this)
        }
        out = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(ScrollView(this).apply { addView(out) },
            LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun log(s: String) = runOnUiThread { out.append(s + "\n") }

    private fun start() {
        val b = base.text.toString().trim()
        val f = from.text.toString().toIntOrNull() ?: 1
        val t = to.text.toString().toIntOrNull() ?: 254
        val u = user.text.toString()
        val p = pass.text.toString()
        val path = logPath.text.toString().trim()
        btn.isEnabled = false
        out.text = ""
        Thread {
            try { scan(b, f, t, u, p, path) }
            catch (e: Exception) { log("Error: ${e.message}") }
            runOnUiThread { btn.isEnabled = true }
        }.start()
    }

    private fun scan(b: String, f: Int, t: Int, u: String, p: String, path: String) {
        log(if (bindEthernet()) "Using Ethernet network" else "Ethernet not found, using default network")
        log("Scanning $b.$f - $b.$t ...")

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
                    log("Found miner: $ip")
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        pool.shutdown()

        if (results.isEmpty()) { log("No miners found."); return }
        val name = save(results.values.joinToString("\n"))
        log("Done. ${results.size} miner(s). Saved: Downloads/WhatsMinerLogs/$name")
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

    /** ذخیره در Downloads/WhatsMinerLogs (بدون نیاز به permission در اندروید 10+) */
    private fun save(text: String): String {
        val name = "whatsminer_logs_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WhatsMinerLogs")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        return name
    }

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
}
