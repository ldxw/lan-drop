package top.ldxw.landrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : android.app.Activity() {

    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var toggleButton: Button
    private lateinit var listBox: LinearLayout
    private lateinit var hintView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastSignature: String = ""

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        urlView = findViewById(R.id.url)
        toggleButton = findViewById(R.id.toggle)
        listBox = findViewById(R.id.fileList)
        hintView = findViewById(R.id.hint)

        ServerService.installCallback = { file -> runOnUiThread { installFile(file) } }

        toggleButton.setOnClickListener {
            if (ServerService.running) {
                ServerService.stop(this)
            } else {
                ensureNotificationPermission()
                ServerService.start(this)
            }
            handler.postDelayed({ refresh() }, 300)
        }

        ensureNotificationPermission()
        ServerService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun refresh() {
        val running = ServerService.running
        val ip = Net.localIpv4()

        if (running && ip != null) {
            statusView.text = "● 正在接收文件"
            urlView.text = "http://$ip:${ServerService.PORT}"
            urlView.visibility = View.VISIBLE
            hintView.text = "手机连同一个 Wi-Fi，用浏览器打开上面这个地址，选文件就能传过来。\n传完的文件会出现在下面，可以直接安装 APK。"
            toggleButton.text = "停止服务"
        } else if (running) {
            statusView.text = "● 已启动，但没连上局域网"
            urlView.text = "请检查电视的网络连接"
            hintView.text = "电视没有拿到局域网 IP。连上 Wi-Fi 或有线网络后会自动刷新。"
            toggleButton.text = "停止服务"
        } else {
            statusView.text = "○ 未启动"
            urlView.visibility = View.GONE
            hintView.text = "点下面的按钮启动服务。"
            toggleButton.text = "启动服务"
        }

        val files = ServerService.server?.let { s ->
            ServerService.receivedDir(this).listFiles()?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        } ?: emptyList()

        val sig = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        if (sig != lastSignature) {
            lastSignature = sig
            renderFiles(files)
        }
    }

    private fun renderFiles(files: List<File>) {
        listBox.removeAllViews()
        if (files.isEmpty()) {
            listBox.addView(textView("还没有收到文件", 16f, 0xFF8B949E.toInt()))
            return
        }
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val name = textView(f.name, 18f, 0xFFE8EAED.toInt()).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                maxLines = 1
            }
            row.addView(name)

            row.addView(smallButton("安装") { installFile(f) })
            row.addView(smallButton("删除") {
                if (f.delete()) {
                    lastSignature = ""
                    refresh()
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
            })
            listBox.addView(row)
        }
    }

    private fun textView(text: String, size: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 15f
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun installFile(file: File) {
        val isApk = file.name.endsWith(".apk", ignoreCase = true)
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = if (isApk) {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, guessMime(file.name))
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打不开：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".mp4", true) -> "video/mp4"
        name.endsWith(".mkv", true) -> "video/x-matroska"
        name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".pdf", true) -> "application/pdf"
        name.endsWith(".zip", true) -> "application/zip"
        else -> "*/*"
    }
}
