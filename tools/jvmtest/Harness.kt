import top.ldxw.landrop.HttpServer
import java.io.File

val mutableSettings = mutableMapOf("proxy" to "", "autoCheck" to "1", "defaultProxy" to "https://gh-proxy.com/")

fun main(args: Array<String>) {
    val dir = File(args[0]).apply { mkdirs() }
    val page = File(args[1]).readText()
    val server = HttpServer(
        port = args[2].toInt(),
        saveDir = dir,
        pageHtml = page,
        onFilesChanged = {},
        onInstallRequest = { f -> println("INSTALL_REQUEST ${f.name}") },
        settingsSnapshot = { mutableSettings },
        settingsApply = { mutableSettings.putAll(it) }
    )
    server.start()
    println("STARTED")
    Runtime.getRuntime().addShutdownHook(Thread { println("SETTINGS " + mutableSettings) })
    Thread.sleep(180_000)
}
