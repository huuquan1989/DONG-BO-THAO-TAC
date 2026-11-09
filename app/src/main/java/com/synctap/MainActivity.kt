package com.synctap

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var countText: TextView
    private lateinit var logText: TextView
    private lateinit var shzStatus: TextView
    private lateinit var testTarget: Button
    private var hits = 0
    private val ui = Handler(Looper.getMainLooper())

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            shzStatus.text = "Quyền Shizuku: " + if (result == PackageManager.PERMISSION_GRANTED) "ĐÃ CẤP" else "BỊ TỪ CHỐI"
        }
    }

    private fun tv(root: LinearLayout, t: String, size: Float = 15f): TextView {
        val v = TextView(this).apply { text = t; textSize = size; setTextColor(Color.BLACK) }
        root.addView(v)
        return v
    }

    private fun btn(root: LinearLayout, t: String, onClick: () -> Unit): Button {
        val b = Button(this).apply { text = t; setOnClickListener { onClick() } }
        root.addView(b)
        return b
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Logger.init(this)
        Shizuku.addRequestPermissionResultListener(permListener)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        tv(root, "SyncTap\n\n1. Mở Cài đặt trợ năng, bật dịch vụ SyncTap.\n" +
            "2. Mở các game clone ở cửa sổ nổi (hoặc split-screen), sắp xếp vị trí.\n" +
            "3. Bấm nút nổi VÙNG để sửa khung: giữ CẠNH để di chuyển cả vùng, kéo GÓC để đổi cỡ. " +
            "+ VÙNG / XOÁ VÙNG để thêm bớt, LƯU VÙNG để lưu, HUỶ SỬA để thoát không lưu.\n" +
            "4. Bấm nút SYNC để bật. Chạm ở cửa sổ nào, các cửa sổ còn lại chạm theo.")
        btn(root, "Mở cài đặt trợ năng") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        countText = TextView(this).apply { textSize = 20f; setPadding(40, 0, 40, 0) }
        fun refresh() { countText.text = "Số cửa sổ mặc định: " + prefs.getInt("panes", 2) }
        row.addView(Button(this).apply {
            text = "-"
            setOnClickListener { prefs.edit().putInt("panes", (prefs.getInt("panes", 2) - 1).coerceAtLeast(1)).apply(); refresh() }
        })
        row.addView(countText)
        row.addView(Button(this).apply {
            text = "+"
            setOnClickListener { prefs.edit().putInt("panes", (prefs.getInt("panes", 2) + 1).coerceAtMost(8)).apply(); refresh() }
        })
        root.addView(row)
        refresh()

        // ----- Shizuku -----
        tv(root, "\n— Shizuku (chạm liên tục, không cần nhấc tay) —", 16f)
        shzStatus = tv(root, "Chưa kiểm tra")
        btn(root, "1. Kiểm tra / cấp quyền Shizuku") { checkShizuku() }
        btn(root, "2. Khởi động Injector, tìm màn cảm ứng") {
            if (!ShizukuBridge.ready()) { shzStatus.text = "Chưa sẵn sàng. Bấm nút 1 trước."; return@btn }
            if (!ShizukuBridge.startInjector(this)) { shzStatus.text = "Không khởi động được Injector (xem log)"; showLog(); return@btn }
            ShizukuBridge.send("FIND")
            shzStatus.text = "Đang tìm màn cảm ứng..."
            ui.postDelayed({
                shzStatus.text = "Màn cảm ứng: ${ShizukuBridge.devPath} max=${ShizukuBridge.maxX}x${ShizukuBridge.maxY}"
                showLog()
            }, 2000)
        }
        testTarget = btn(root, "ĐIỂM THỬ (chưa nhận chạm bơm)") {
            hits++
            testTarget.text = "ĐIỂM THỬ: đã nhận chạm $hits lần"
        }
        btn(root, "3. Thử bơm một chạm vào ĐIỂM THỬ") {
            if (!ShizukuBridge.ready()) { shzStatus.text = "Chưa sẵn sàng. Bấm nút 1 trước."; return@btn }
            if (!ShizukuBridge.startInjector(this)) { showLog(); return@btn }
            val loc = IntArray(2)
            testTarget.getLocationOnScreen(loc)
            val x = loc[0] + testTarget.width / 2
            val y = loc[1] + testTarget.height / 2
            ShizukuBridge.send("TAP $x $y")
            ui.postDelayed({ showLog() }, 1200)
        }
        val modeBtn = Button(this)
        fun refreshMode() {
            modeBtn.text = if (prefs.getBoolean("shizuku", false)) "Chế độ SYNC: SHIZUKU (chạm liên tục)"
                           else "Chế độ SYNC: GHI RỒI PHÁT (ổn định, trễ)"
        }
        modeBtn.setOnClickListener {
            prefs.edit().putBoolean("shizuku", !prefs.getBoolean("shizuku", false)).apply(); refreshMode()
        }
        refreshMode()
        root.addView(modeBtn)

        // ----- Log -----
        tv(root, "\n— Log —", 16f)
        val logRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logRow.addView(Button(this).apply { text = "Xem log"; setOnClickListener { showLog() } })
        logRow.addView(Button(this).apply {
            text = "Sao chép"
            setOnClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("synctap-log", Logger.dump()))
                Toast.makeText(this@MainActivity, "Đã sao chép log", Toast.LENGTH_SHORT).show()
            }
        })
        logRow.addView(Button(this).apply { text = "Xoá log"; setOnClickListener { Logger.clear(); showLog() } })
        root.addView(logRow)
        logText = TextView(this).apply { textSize = 10f; setTextColor(Color.DKGRAY); setTextIsSelectable(true) }
        root.addView(logText)

        setContentView(ScrollView(this).apply { addView(root) })
        showLog()
    }

    private fun checkShizuku() {
        val alive = ShizukuBridge.binderAlive()
        val perm = ShizukuBridge.hasPermission()
        Logger.d("UI", "Shizuku binder=$alive quyen=$perm uid=${ShizukuBridge.serverUid()}")
        if (!alive) {
            shzStatus.text = "Shizuku chưa chạy. Mở app Shizuku và khởi động (gỡ lỗi không dây) trước."
        } else if (!perm) {
            shzStatus.text = "Đang xin quyền... hãy bấm Cho phép."
            try { Shizuku.requestPermission(100) } catch (e: Throwable) { shzStatus.text = "Lỗi xin quyền: ${e.message}" }
        } else {
            shzStatus.text = "Shizuku OK (server uid=${ShizukuBridge.serverUid()}, 2000 = shell)"
        }
        showLog()
    }

    override fun onResume() { super.onResume(); showLog() }
    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(permListener); super.onDestroy() }
    private fun showLog() { logText.text = Logger.dump().takeLast(12000) }
}
