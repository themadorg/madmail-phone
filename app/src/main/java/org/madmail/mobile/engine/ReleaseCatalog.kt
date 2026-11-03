package org.madmail.mobile.engine

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal data class UpstreamRelease(val version: String, val url: String, val digest: String, val bytes: Long)
internal data class ReleaseAsset(val name: String, val url: String, val digest: String, val bytes: Long)
internal data class GitRelease(val version: String, val assets: List<ReleaseAsset>) {
    // Package identity, signer and version are checked again by AppUpdates before installation.
    val androidPackage: ReleaseAsset? get() = assets.firstOrNull {
        it.name.endsWith(".apk") && (it.name.contains("arm64", true) || it.name.contains("universal", true))
    } ?: assets.singleOrNull { it.name.endsWith(".apk") && !it.name.contains("x86", true) && !it.name.contains("armeabi", true) }
}
internal object ReleaseCatalog {
    fun list(): List<GitRelease> {
        val releases = mutableListOf<GitRelease>()
        for (page in 1..10) {
            val connection = URL("https://api.github.com/repos/themadorg/madmail/releases?per_page=100&page=$page").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15000; connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "Madmail-Mobile")
                check(connection.responseCode == 200) {
                    if (connection.responseCode in listOf(403, 429)) "GitHub rate limit reached. Try again later."
                    else "Couldn’t load releases (${connection.responseCode})."
                }
                val array = JSONArray(String(connection.inputStream.use { it.readBytesLimited(8 * 1024 * 1024) }))
                releases += parse(array)
                if (array.length() < 100) break
            } finally { connection.disconnect() }
        }
        return releases.distinctBy { it.version }.sortedWith { a, b -> compareVersions(b.version, a.version) }
    }
    internal fun parse(list: JSONArray): List<GitRelease> =
        (0 until list.length()).map { list.getJSONObject(it) }.filter { !it.optBoolean("prerelease") && !it.optBoolean("draft") }
            .mapNotNull { release ->
                val version = release.getString("tag_name").removePrefix("v")
                if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version)) return@mapNotNull null
                val assets = release.optJSONArray("assets") ?: JSONArray()
                GitRelease(version, (0 until assets.length()).map { assets.getJSONObject(it) }.mapNotNull assetMap@{ asset ->
                    val url = asset.optString("browser_download_url")
                    if (!url.startsWith("https://github.com/themadorg/madmail/releases/download/")) return@assetMap null
                    ReleaseAsset(asset.getString("name"), url, asset.optString("digest").removePrefix("sha256:"), asset.optLong("size"))
                })
            }
    fun releases(architecture: String): List<UpstreamRelease> {
        require(architecture in listOf("arm64", "amd64"))
        return list().mapNotNull { release ->
            val asset = release.assets.find { it.name == "madmail-linux-$architecture-musl" } ?: return@mapNotNull null
            if (!Regex("[a-f0-9]{64}").matches(asset.digest)) return@mapNotNull null
            UpstreamRelease(release.version, asset.url, asset.digest, asset.bytes)
        }
    }
    fun download(release: UpstreamRelease, target: File) {
        require(release.bytes in 1..200L * 1024 * 1024)
        var url = URL(release.url)
        target.parentFile!!.mkdirs()
        try {
            repeat(6) {
                require(url.protocol == "https")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000; connection.readTimeout = 30000; connection.instanceFollowRedirects = false
                try {
                    if (connection.responseCode in listOf(301,302,303,307,308)) url = URL(url, connection.getHeaderField("Location"))
                    else {
                        check(connection.responseCode == 200) { "Release download failed." }
                        connection.inputStream.use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(65536); var total = 0L
                            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; check(total <= release.bytes); output.write(buffer, 0, count) }
                        } }
                        check(target.length() == release.bytes && sha256(target) == release.digest) { "Release checksum mismatch." }
                        return
                    }
                } finally { connection.disconnect() }
            }
            error("Too many release redirects.")
        } catch (e: Exception) { target.delete(); throw e }
    }
}
