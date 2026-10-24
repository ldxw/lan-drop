import top.ldxw.landrop.HttpServer
import java.io.File

fun main(args: Array<String>) {
    val dir = File(args[0]).apply { mkdirs() }
    val page = File(args[1]).readText()
    val server = HttpServer(
        port = args[2].toInt(),
        saveDir = dir,
        pageHtml = page,
        onFilesChanged = {},
        onInstallRequest = { f -> println("INSTALL_REQUEST ${f.name}") }
    )
    server.start()
    println("STARTED")
    Thread.sleep(180_000)
}
