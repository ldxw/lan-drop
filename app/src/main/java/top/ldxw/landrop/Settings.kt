package top.ldxw.landrop

import android.content.Context
import android.content.SharedPreferences

/** 电视端的设置，手机通过上传页也能改（改完立即生效）。 */
class Settings private constructor(private val sp: SharedPreferences) {

    companion object {
        const val DEFAULT_PROXY = "https://gh-proxy.com/"
        val PRESETS = listOf(
            "" to "直连 GitHub",
            "https://gh-proxy.com/" to "gh-proxy.com",
            "https://ghfast.top/" to "ghfast.top",
            "https://ghproxy.net/" to "ghproxy.net"
        )

        @Volatile
        private var instance: Settings? = null

        fun of(ctx: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(
                    ctx.applicationContext.getSharedPreferences("landrop", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }

    var proxy: String
        get() = sp.getString("proxy", DEFAULT_PROXY) ?: DEFAULT_PROXY
        set(value) { sp.edit().putString("proxy", value.trim()).apply() }

    var autoCheck: Boolean
        get() = sp.getBoolean("autoCheck", true)
        set(value) { sp.edit().putBoolean("autoCheck", value).apply() }

    fun snapshot(): Map<String, String> = mapOf(
        "proxy" to proxy,
        "autoCheck" to if (autoCheck) "1" else "0",
        "defaultProxy" to DEFAULT_PROXY
    )

    fun apply(map: Map<String, String>) {
        map["proxy"]?.let { proxy = it }
        map["autoCheck"]?.let { autoCheck = it == "1" || it.equals("true", true) }
    }
}
