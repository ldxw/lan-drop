package top.ldxw.landrop

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 HTTP 服务：只做三件事
 *  - GET  /            手机浏览器打开的上传页面（选文件 → PUT 原始流上传，带进度）
 *  - PUT  /upload      原始字节写入文件（不走 multipart，省掉一整套解析）
 *  - 其余 /api/*、/d、/install、/delete 供页面和电视端调用
 */
class HttpServer(
    private val port: Int,
    private val saveDir: File,
    private val pageHtml: String,
    private val onFilesChanged: () -> Unit,
    private val onInstallRequest: (File) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.get()) return
        saveDir.mkdirs()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        running.set(true)
        pool.execute {
            while (running.get()) {
                val socket = try {
                    ss.accept()
                } catch (e: Exception) {
                    if (running.get()) e.printStackTrace()
                    null
                } ?: continue
                pool.execute { handle(socket) }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    // ---------------------------------------------------------------- 请求分发

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 300_000
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)

            val headerText = readHeader(input) ?: return
            val lines = headerText.split("\r\n")
            val first = lines.firstOrNull()?.split(" ") ?: return
            if (first.size < 2) return
            val method = first[0].uppercase()
            val target = first[1]
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) {
                    headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
                }
            }

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> sendHtml(out, page())
                method == "GET" && path == "/api/files" -> sendJson(out, filesJson())
                method == "GET" && path == "/d" -> sendFile(out, query["name"])
                method == "PUT" && path == "/upload" -> receiveUpload(out, input, headers, query)
                method == "POST" && path == "/upload" -> receiveUpload(out, input, headers, query)
                method == "POST" && path == "/install" -> {
                    val f = safeFile(query["name"])
                    if (f != null && f.exists()) {
                        onInstallRequest(f)
                        sendJson(out, """{"ok":true,"msg":"已在电视上弹出安装确认"}""")
                    } else {
                        sendJson(out, """{"ok":false,"msg":"文件不存在"}""")
                    }
                }
                method == "POST" && path == "/delete" -> {
                    val f = safeFile(query["name"])
                    val ok = f != null && f.exists() && f.delete()
                    if (ok) onFilesChanged()
                    sendJson(out, """{"ok":$ok}""")
                }
                else -> sendText(out, 404, "Not Found")
            }
            out.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readHeader(input: InputStream): String? {
        val buf = StringBuilder()
        var state = 0
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.isEmpty()) null else buf.toString()
            total++
            if (total > 64 * 1024) return null
            buf.append(b.toChar())
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) return buf.toString().dropLast(4) else 0
                else -> 0
            }
        }
    }

    // ---------------------------------------------------------------- 上传

    private fun receiveUpload(
        out: OutputStream,
        input: InputStream,
        headers: Map<String, String>,
        query: Map<String, String>
    ) {
        val rawName = query["name"] ?: headers["x-file-name"] ?: "upload.bin"
        val name = sanitize(rawName)
        if (name.isEmpty()) {
            sendJson(out, """{"ok":false,"msg":"文件名非法"}""")
            return
        }
        val length = headers["content-length"]?.toLongOrNull() ?: -1L
        val target = File(saveDir, uniqueName(name))
        var written = 0L
        try {
            FileOutputStream(target).use { fos ->
                val buf = ByteArray(256 * 1024)
                if (length >= 0) {
                    var remain = length
                    while (remain > 0) {
                        val read = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                        if (read < 0) break
                        fos.write(buf, 0, read)
                        remain -= read
                        written += read
                    }
                } else {
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        fos.write(buf, 0, read)
                        written += read
                    }
                }
            }
            onFilesChanged()
            val jsonName = jsonEscape(target.name)
            sendJson(out, """{"ok":true,"name":"$jsonName","size":$written}""")
        } catch (e: Exception) {
            target.delete()
            sendJson(out, """{"ok":false,"msg":"${jsonEscape(e.message ?: "写入失败")}"}""")
        }
    }

    private fun uniqueName(name: String): String {
        var f = File(saveDir, name)
        if (!f.exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            f = File(saveDir, "$base($i)$ext")
            if (!f.exists()) return f.name
            i++
        }
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base.replace(Regex("[\\u0000-\\u001f]"), "").trim().take(180)
    }

    private fun safeFile(name: String?): File? {
        if (name.isNullOrBlank()) return null
        val n = sanitize(name)
        if (n.isEmpty() || n == "." || n == "..") return null
        val f = File(saveDir, n)
        return if (f.canonicalPath.startsWith(saveDir.canonicalPath + File.separator)) f else null
    }

    // ---------------------------------------------------------------- 输出

    private fun filesJson(): String {
        val list = saveDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        val items = list.joinToString(",") { f ->
            """{"name":"${jsonEscape(f.name)}","size":${f.length()},"time":${f.lastModified()}}"""
        }
        return """{"ok":true,"files":[$items]}"""
    }

    private fun sendFile(out: OutputStream, name: String?) {
        val f = safeFile(name)
        if (f == null || !f.exists()) {
            sendText(out, 404, "Not Found")
            return
        }
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${f.length()}\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''${URLEncoder.encode(f.name, "UTF-8")}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        f.inputStream().use { it.copyTo(out, 256 * 1024) }
    }

    private fun sendHtml(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
    }

    private fun sendJson(out: OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
    }

    private fun sendText(out: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 404) "Not Found" else "OK"}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = if (pair.contains('=')) pair.substringAfter('=') else ""
            map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return map
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    // ---------------------------------------------------------------- 页面

    private fun page(): String = pageHtml
}
