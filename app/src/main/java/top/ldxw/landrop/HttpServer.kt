package top.ldxw.landrop

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 HTTP 服务，包含四个能力：
 *  - GET  /              手机浏览器打开的上传页面（整文件夹、断点续传都在页面里做）
 *  - PUT  /upload?...    ？offset=N 表示从这个字节位置续写，不带 offset 就从头写
 *  - GET  /api/stat      查询某个文件已收到多少字节，用来续传
 *  - GET  /api/files     递归列出已收到的文件
 */
class HttpServer(
    private val port: Int,
    private val saveDir: File,
    private val pageHtml: String,
    private val onFilesChanged: () -> Unit,
    private val onInstallRequest: (File) -> Unit,
    private val settingsSnapshot: () -> Map<String, String>,
    private val settingsApply: (Map<String, String>) -> Unit
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
            socket.soTimeout = 600_000
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
                method == "GET" && (path == "/" || path == "/index.html") -> sendHtml(out, pageHtml)
                method == "GET" && path == "/api/files" -> sendJson(out, filesJson())
                method == "GET" && path == "/api/stat" -> sendJson(out, statJson(query["name"]))
                method == "GET" && path == "/d" -> sendFile(out, query["name"])
                method == "GET" && path == "/api/settings" -> sendJson(out, settingsJson(settingsSnapshot()))
                method == "GET" && path == "/api/ping" -> sendJson(out, pingJson(query["url"] ?: ""))
                method == "POST" && path == "/api/settings" -> {
                    settingsApply(query)
                    sendJson(out, settingsJson(settingsSnapshot()))
                }
                method == "PUT" && path == "/upload" -> receiveUpload(out, input, headers, query)
                method == "POST" && path == "/upload" -> receiveUpload(out, input, headers, query)
                method == "POST" && path == "/install" -> {
                    val f = safeFile(query["name"])
                    if (f != null && f.isFile) {
                        onInstallRequest(f)
                        sendJson(out, "{\"ok\":true,\"msg\":\"已在电视上弹出安装确认\"}")
                    } else {
                        sendJson(out, "{\"ok\":false,\"msg\":\"文件不存在\"}")
                    }
                }
                method == "POST" && path == "/delete" -> {
                    val f = safeFile(query["name"])
                    val ok = f != null && f.isFile && f.delete()
                    if (ok) {
                        pruneEmptyDirs(f?.parentFile)
                        onFilesChanged()
                    }
                    sendJson(out, "{\"ok\":$ok}")
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

    // ---------------------------------------------------------------- 上传 / 续传

    private fun receiveUpload(
        out: OutputStream,
        input: InputStream,
        headers: Map<String, String>,
        query: Map<String, String>
    ) {
        val rawName = query["name"] ?: headers["x-file-name"] ?: "upload.bin"
        val target = safeFile(rawName)
        if (target == null) {
            sendJson(out, "{\"ok\":false,\"msg\":\"文件名非法\"}")
            return
        }
        val offset = query["offset"]?.toLongOrNull() ?: 0L
        val length = headers["content-length"]?.toLongOrNull() ?: -1L

        if (offset > 0 && !target.isFile) {
            sendJson(out, "{\"ok\":false,\"restart\":true,\"msg\":\"文件已不存在，需要重新开始\"}")
            return
        }
        if (offset > 0 && offset > target.length()) {
            sendJson(out, "{\"ok\":false,\"restart\":true,\"msg\":\"偏移超出已有长度\"}")
            return
        }

        try {
            target.parentFile?.mkdirs()
            RandomAccessFile(target, "rw").use { raf ->
                if (offset == 0L) raf.setLength(0) else raf.seek(offset)
                val buf = ByteArray(256 * 1024)
                if (length >= 0) {
                    var remain = length
                    while (remain > 0) {
                        val read = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                        if (read < 0) break
                        raf.write(buf, 0, read)
                        remain -= read
                    }
                } else {
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        raf.write(buf, 0, read)
                    }
                }
            }
            onFilesChanged()
            val rel = relName(target)
            sendJson(out, "{\"ok\":true,\"name\":\"${jsonEscape(rel)}\",\"size\":${target.length()}}")
        } catch (e: Exception) {
            sendJson(out, "{\"ok\":false,\"msg\":\"${jsonEscape(e.message ?: "写入失败")}\"}")
        }
    }

    /** 测加速地址通不通：拉一次 update.json，看耗时。 */
    private fun pingJson(proxy: String): String {
        val start = System.currentTimeMillis()
        val body = Update.fetch(Update.withProxy(proxy, Update.UPDATE_URL), 8000)
        val ms = System.currentTimeMillis() - start
        return if (body != null) "{\"ok\":true,\"ms\":$ms}" else "{\"ok\":false,\"ms\":$ms}"
    }

    private fun settingsJson(map: Map<String, String>): String {
        val body = map.entries.joinToString(",") { (k, v) ->
            "\"" + jsonEscape(k) + "\":\"" + jsonEscape(v) + "\""
        }
        return "{\"ok\":true,\"settings\":{$body}}"
    }

    private fun statJson(name: String?): String {
        val f = safeFile(name)
        return if (f != null && f.isFile) {
            "{\"ok\":true,\"exists\":true,\"size\":${f.length()}}"
        } else {
            "{\"ok\":true,\"exists\":false,\"size\":0}"
        }
    }

    // ---------------------------------------------------------------- 路径

    /**
     * 支持 a/b/c.apk 这样的相对路径（整文件夹上传），逐段过滤，挡住 .. 和绝对路径。
     */
    private fun safeFile(name: String?): File? {
        if (name.isNullOrBlank()) return null
        val parts = name.replace('\\', '/').split('/')
            .map { sanitizeSegment(it) }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var f = saveDir
        for (p in parts) f = File(f, p)
        val root = saveDir.canonicalPath + File.separator
        return if (f.canonicalPath.startsWith(root)) f else null
    }

    private fun sanitizeSegment(seg: String): String {
        val s = seg.replace(Regex("[\\u0000-\\u001f]"), "").trim()
        if (s == "." || s == "..") return ""
        return s.take(120)
    }

    private fun relName(f: File): String =
        f.canonicalPath.removePrefix(saveDir.canonicalPath + File.separator).replace(File.separatorChar, '/')

    private fun pruneEmptyDirs(dir: File?) {
        var d = dir ?: return
        while (d.canonicalPath != saveDir.canonicalPath && d.isDirectory && (d.list()?.isEmpty() ?: false)) {
            d.delete()
            d = d.parentFile ?: return
        }
    }

    private fun walk(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) walk(c, out) else out.add(c)
        }
    }

    // ---------------------------------------------------------------- 输出

    private fun filesJson(): String {
        val all = ArrayList<File>()
        walk(saveDir, all)
        val sorted = all.sortedByDescending { it.lastModified() }
        val items = sorted.joinToString(",") { f ->
            "{\"name\":\"${jsonEscape(relName(f))}\",\"size\":${f.length()},\"time\":${f.lastModified()}}"
        }
        return "{\"ok\":true,\"files\":[$items]}"
    }

    private fun sendFile(out: OutputStream, name: String?) {
        val f = safeFile(name)
        if (f == null || !f.isFile) {
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
}
