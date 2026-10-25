package top.ldxw.landrop

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查与下载。只用 java.net，不依赖 Android，能在 JVM 上直接测。
 */
object Update {

    const val REPO = "ldxw/lan-drop"
    const val UPDATE_URL = "https://github.com/$REPO/releases/latest/download/update.json"

    data class Info(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val notes: String
    )

    /** 把加速地址拼到真实地址前面；空串表示直连。 */
    fun withProxy(proxy: String, url: String): String {
        val p = proxy.trim()
        if (p.isEmpty()) return url
        return p.trimEnd('/') + "/" + url
    }

    fun fetch(url: String, timeoutMs: Int = 15000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LanDrop")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 从 update.json 里取字段。字段固定，用正则解析，避免引入 JSON 库。 */
    fun parse(json: String): Info? {
        val version = str(json, "version") ?: return null
        val code = num(json, "versionCode") ?: return null
        val url = str(json, "url") ?: return null
        val notes = str(json, "notes") ?: ""
        return Info(version, code, url, notes)
    }

    private fun str(json: String, key: String): String? {
        val m = Regex("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
        return m.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
    }

    private fun num(json: String, key: String): Int? {
        val m = Regex("\"" + key + "\"\\s*:\\s*(\\d+)").find(json) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    fun isNewer(info: Info, currentCode: Int): Boolean = info.versionCode > currentCode

    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LanDrop")
            }
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            dest.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}
