package top.ldxw.landrop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.File

class MainActivity : android.app.Activity() {

    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var qrView: ImageView
    private lateinit var qrHint: TextView
    private lateinit var toggleButton: Button
    private lateinit var listBox: LinearLayout
    private lateinit var hintView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastSignature: String = ""
    private var lastQrUrl: String = ""

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
        qrView = findViewById(R.id.qr)
        qrHint = findViewById(R.id.qrHint)
        toggleButton = findViewById(R.id.toggle)
        listBox = findViewById(R.id.fileList)
        hintView = findViewById(R.id.hint)

        ServerService.installCallback = { file -> runOnUiThread { installFile(file) } }

        toggleButton.setOnClickListener {
            if (ServerService.running) ServerService.stop(this) else {
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
            val url = "http://$ip:${ServerService.PORT}"
            statusView.text = "● 正在接收文件"
            urlView.text = url
            urlView.visibility = View.VISIBLE
            if (url != lastQrUrl) {
                lastQrUrl = url
                qrView.setImageBitmap(qrBitmap(url, 720))
            }
            qrView.visibility = View.VISIBLE
            qrHint.visibility = View.VISIBLE
            hintView.text = "用手机相机扫上面的码，或浏览器输入这个地址；手机和电视要连同一个 Wi-Fi。\n传完的文件在下面，可以直接安装 APK。"
            toggleButton.text = "停止服务"
        } else if (running) {
            statusView.text = "● 已启动，但没连上局域网"
            urlView.text = "请检查电视的网络连接"
            urlView.visibility = View.VISIBLE
            qrView.visibility = View.GONE
            qrHint.visibility = View.GONE
            hintView.text = "电视没有拿到局域网 IP。连上 Wi-Fi 或有线网络后会自动刷新。"
            toggleButton.text = "停止服务"
        } else {
            statusView.text = "○ 未启动"
            urlView.visibility = View.GONE
            qrView.visibility = View.GONE
            qrHint.visibility = View.GONE
            hintView.text = "点下面的按钮启动服务。"
            toggleButton.text = "启动服务"
        }

        val files = if (running) Storage.walk(ServerService.receivedDir(this))
            .sortedByDescending { it.lastModified() } else emptyList()
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
        val root = ServerService.receivedDir(this)
        for (f in files) {
            val rel = Storage.relName(root, f)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            column.addView(textView(f.name, 18f, 0xFFE8EAED.toInt()).apply {
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                maxLines = 1
            })
            if (rel != f.name) {
                column.addView(textView(rel, 13f, 0xFF6B7684.toInt()).apply {
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    maxLines = 1
                })
            }
            row.addView(column)
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

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
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
