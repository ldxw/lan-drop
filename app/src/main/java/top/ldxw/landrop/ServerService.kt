package top.ldxw.landrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/**
 * 前台服务：让 HTTP 服务在电视熄屏/切后台时继续工作。
 */
class ServerService : Service() {

    companion object {
        const val PORT = 8888
        private const val CHANNEL_ID = "landrop"
        private const val NOTI_ID = 1001

        const val ACTION_START = "top.ldxw.landrop.START"
        const val ACTION_STOP = "top.ldxw.landrop.STOP"

        @Volatile
        var server: HttpServer? = null
            private set

        @Volatile
        var running: Boolean = false
            private set

        /** Activity 里设置：收到安装请求时回调（在主线程执行） */
        @Volatile
        var installCallback: ((File) -> Unit)? = null

        fun receivedDir(ctx: Context): File {
            val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "received")
            dir.mkdirs()
            return dir
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, ServerService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        if (running) return
        createChannel()
        startForeground(NOTI_ID, notification(Net.localIpv4() ?: "未连接"))
        acquireWakeLock()

        val pageHtml = try {
            resources.openRawResource(R.raw.page).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<h1>page.html missing</h1>"
        }

        val s = HttpServer(
            port = PORT,
            saveDir = receivedDir(this),
            pageHtml = pageHtml,
            onFilesChanged = {},
            onInstallRequest = { file ->
                val cb = installCallback
                android.os.Handler(mainLooper).post { cb?.invoke(file) }
            },
            settingsSnapshot = { Settings.of(this).snapshot() },
            settingsApply = { Settings.of(this).apply(it) }
        )
        try {
            s.start()
            server = s
            running = true
        } catch (e: Exception) {
            e.printStackTrace()
            running = false
            server = null
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        server = null
        running = false
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lan-drop:server").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "局域网快传", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "接收文件时保持后台运行"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun notification(ip: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("局域网快传运行中")
            .setContentText("http://$ip:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
