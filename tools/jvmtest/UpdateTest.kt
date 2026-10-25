import top.ldxw.landrop.Update

fun main() {
    val json = """{"version":"1.3.0","versionCode":4,"url":"https://github.com/ldxw/lan-drop/releases/download/v1.3.0/app-release.apk","notes":"修了点东西\n第二条"}"""
    val info = Update.parse(json)
    println("PARSE " + (info?.versionName ?: "null") + " / code=" + (info?.versionCode ?: -1))
    println("NOTES_MULTILINE " + (info?.notes?.contains("\n") == true))
    println("NEWER_3 " + Update.isNewer(info!!, 3))
    println("NEWER_5 " + Update.isNewer(info, 5))
    println("PROXY " + Update.withProxy("https://gh-proxy.com/", "https://github.com/x"))
    println("PROXY_BLANK " + Update.withProxy("", "https://github.com/x"))
    println("PROXY_SLASH " + Update.withProxy("https://gh-proxy.com", "https://github.com/x"))
    println("PARSE_BAD " + (Update.parse("not json") == null))
    println("UPDATE_TEST_DONE")
}
