package org.madmail.mobile.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Android's package installer activates signed native code; never execute downloaded files. */
internal class AppUpdates(private val context: Context) {
    private var staged: File? = null
    fun download(address: String): String {
        var url = URL(address.trim())
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val unique = java.util.UUID.randomUUID().toString()
        val partial = File(directory, "$unique.part")
        val ready = File(directory, "$unique.apk")
        try {
            repeat(6) {
                require(url.protocol == "https" && url.userInfo == null) { "Use an HTTPS package link without credentials." }
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000; connection.readTimeout = 30000; connection.instanceFollowRedirects = false
                try {
                    if (connection.responseCode in listOf(301, 302, 303, 307, 308)) {
                        url = URL(url, connection.getHeaderField("Location") ?: error("Invalid redirect."))
                    } else {
                        check(connection.responseCode == 200) { "Download failed (${connection.responseCode})." }
                        connection.inputStream.use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(65536); var size = 0L
                            while (true) { val count = input.read(buffer); if (count < 0) break; size += count; check(size <= 250L * 1024 * 1024) { "Package exceeds 250 MB." }; output.write(buffer, 0, count) }
                        } }
                        val label = verify(partial)
                        check(partial.renameTo(ready)) { "Unable to stage update." }
                        staged = ready
                        return label
                    }
                } finally { connection.disconnect() }
            }
            error("Too many redirects.")
        } finally { partial.delete() }
    }
    @Suppress("DEPRECATION")
    fun verify(file: File): String {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = manager.getPackageArchiveInfo(file.path, flags) ?: error("Not a valid Android package.")
        val current = manager.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "This package is not Madmail Mobile." }
        val expected = if (Build.VERSION.SDK_INT >= 28) current.signingInfo!!.apkContentsSigners else current.signatures!!
        val actual = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo!!.apkContentsSigners else archive.signatures!!
        require(actual.toSet() == expected.toSet()) { "Update signer does not match this installed app." }
        val next = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val old = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        require(next > old) { "Choose a newer app package. Downgrades are blocked to protect server data." }
        return archive.versionName ?: next.toString()
    }
    fun install() {
        check(LocalEngine.get(context).state.value.phase in listOf(EnginePhase.Stopped, EnginePhase.Failed)) { "Stop hosting before updating." }
        val file = checkNotNull(staged) { "Download an update first." }
        verify(file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
