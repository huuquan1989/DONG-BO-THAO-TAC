package com.synctap

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.TouchInteractionController
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Lớp phủ vẽ khung các vùng; ở chế độ sửa có thêm tay nắm 4 góc và số thứ tự. */
class RectView(c: Context) : View(c) {
    val rects = ArrayList<RectF>()
    var selected = -1
    var editing = false
    private val green = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.GREEN }
    private val yellow = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.YELLOW }
    private val handle = Paint().apply { style = Paint.Style.FILL; color = Color.parseColor("#DDFFFFFF") }
    private val label = Paint().apply { color = Color.WHITE; textSize = 52f; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
    private val loc = IntArray(2)

    override fun onDraw(c: Canvas) {
        getLocationOnScreen(loc)
        c.translate(-loc[0].toFloat(), -loc[1].toFloat())   // vẽ theo toạ độ màn hình
        rects.forEachIndexed { i, r ->
            val sel = editing && i == selected
            c.drawRect(r, if (sel) yellow else green)
            if (editing) {
                val hs = 22f
                for (k in 0..3) {
                    val x = if (k == 0 || k == 3) r.left else r.right
                    val y = if (k < 2) r.top else r.bottom
                    c.drawRect(x - hs, y - hs, x + hs, y + hs, handle)
                }
                c.drawText("${i + 1}", r.left + 36f, r.top + 70f, label)
            }
        }
    }
}

class SyncService : AccessibilityService() {

    companion object {
        const val FIRST_DELAY = 30L     // chờ cờ NOT_TOUCHABLE có hiệu lực trước nét đầu tiên
        const val EDGE = 50f            // độ dày vùng bắt cạnh (kéo cả vùng)
        const val CORNER = 80f          // bán kính bắt góc (đổi cỡ)
        const val MIN_SIZE = 150f
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var capture: RectView? = null
    private var capParams: WindowManager.LayoutParams? = null
    private var syncBtn: TextView? = null
    private var syncParams: WindowManager.LayoutParams? = null
    private var zoneBtn: TextView? = null
    private var zoneParams: WindowManager.LayoutParams? = null
    private val editBtns = ArrayList<Pair<TextView, WindowManager.LayoutParams>>()

    private var syncOn = false
    private var editing = false
    private var rects = ArrayList<RectF>()

    // --- sửa vùng ---
    private var dragMode = 0        // 0: không, 1: kéo cả vùng, 2: đổi cỡ
    private var dragIdx = -1
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastX = 0f
    private var lastY = 0f

    // --- chế độ HỆ THỐNG (TouchInteractionController) ---
    private var systemMode = false
    private var shzMirror = false          // đang phản chiếu cảm ứng bằng Shizuku
    private var tic: TouchInteractionController? = null
    private var ticCb: TouchInteractionController.Callback? = null
    private var delegated = false

    // --- chế độ "ghi rồi phát" ---
    private var injecting = false
    private val points = ArrayList<PointF>()
    private var downTime = 0L

    // --- chế độ realtime ---
    private var curStream = true
    private var streamActive = false
    private var sBusy = false
    private var sFirst = true
    private var sUp = false
    private var sMoved = false
    private var sSeg = 0
    private var sDownTime = 0L
    private var sLastSeg = 0L
    private var sStart = PointF()
    private var sLast = PointF()
    private val sPending = ArrayList<PointF>()
    private var sSrc = RectF()
    private var sDst: List<RectF> = emptyList()
    private var sStrokes: Array<GestureDescription.StrokeDescription?> = arrayOfNulls(0)

    private fun log(m: String) = Logger.d("SVC", m)
    private fun rs(r: RectF) = "[${r.left.toInt()},${r.top.toInt()} - ${r.right.toInt()},${r.bottom.toInt()}]"
    private fun prefs() = getSharedPreferences("cfg", MODE_PRIVATE)
    private fun paneCount() = prefs().getInt("panes", 2).coerceIn(1, 8)
    private fun mode() = 1          // chỉ còn chế độ "ghi rồi phát" (các chế độ khác gây đơ/huỷ chạm trên máy thật)
    private fun streamMode() = mode() == 0 || systemMode
    private fun zoneLabel() = if (rects.isEmpty()) "VÙNG: chưa cài" else "VÙNG ✓ (${rects.size}) - sửa"

    private fun screenSize(): Pair<Float, Float> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        Logger.init(applicationContext)
        val (w, h) = screenSize()
        log("=== Service connected | ${Build.MODEL} | Android ${Build.VERSION.SDK_INT} | screen ${w.toInt()}x${h.toInt()} ===")
        loadRects()
        log("loaded rects=${rects.map { rs(it) }} panes=${paneCount()} stream=${streamMode()}")
        val s = makeButton("SYNC OFF", 20, 300) { toggleSync() }
        syncBtn = s.first; syncParams = s.second
        val z = makeButton(zoneLabel(), 20, 420) { if (editing) exitEditor(true) else enterEditor() }
        zoneBtn = z.first; zoneParams = z.second
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopShizukuMirror()
        removeCapture()
        clearEditButtons()
        syncBtn?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        zoneBtn?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }

    // ---------- Lưu / đọc vùng ----------
    private fun saveRects() {
        val s = rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
        prefs().edit().putString("rects", s).putInt("panes", rects.size.coerceIn(1, 8)).apply()
    }

    private fun loadRects() {
        rects.clear()
        val s = prefs().getString("rects", "") ?: ""
        if (s.isBlank()) return
        for (part in s.split(";")) {
            val v = part.split(",").mapNotNull { it.toFloatOrNull() }
            if (v.size == 4) rects.add(RectF(v[0], v[1], v[2], v[3]))
        }
    }

    // ---------- Nút nổi ----------
    private fun makeButton(label: String, x0: Int, y0: Int, onClick: () -> Unit):
        Pair<TextView, WindowManager.LayoutParams> {
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(28, 18, 28, 18)
            background = GradientDrawable().apply {
                cornerRadius = 40f
                setColor(Color.parseColor("#CC555555"))
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = x0; y = y0 }

        var dx0 = 0f; var dy0 = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dx0; val dy = e.rawY - dy0
                    if (abs(dx) > 15 || abs(dy) > 15) moved = true
                    if (moved) { lp.x = ox + dx.toInt(); lp.y = oy + dy.toInt(); wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onClick()
            }
            true
        }
        wm.addView(tv, lp)
        return Pair(tv, lp)
    }

    private fun setColor(tv: TextView?, hex: String) {
        (tv?.background as? GradientDrawable)?.setColor(Color.parseColor(hex))
    }

    private fun bringButtonsToTop() {
        syncBtn?.let { wm.removeView(it); wm.addView(it, syncParams) }
        zoneBtn?.let { wm.removeView(it); wm.addView(it, zoneParams) }
        editBtns.forEach { wm.removeView(it.first); wm.addView(it.first, it.second) }
    }

    private fun clearEditButtons() {
        editBtns.forEach { try { wm.removeView(it.first) } catch (_: Exception) {} }
        editBtns.clear()
    }

    // ---------- Bật/tắt đồng bộ ----------
    private fun toggleSync() {
        if (editing) { exitEditor(false); return }       // đang sửa: bấm = huỷ
        syncOn = !syncOn
        log("toggleSync -> ${if (syncOn) "ON" else "OFF"} (rects=${rects.size}, stream=${streamMode()})")
        if (syncOn) {
            systemMode = false
            shzMirror = useShizuku() && startShizukuMirror()
            addCapture(touchable = !shzMirror)
            capture?.rects?.apply { clear(); rects.forEach { add(RectF(it)) } }
            capture?.invalidate()
            bringButtonsToTop()
            syncBtn?.text = "SYNC ON"; setColor(syncBtn, "#CC1B9E3C")
        } else {
            endStream("tat sync")
            stopShizukuMirror()
            removeCapture()
            syncBtn?.text = "SYNC OFF"; setColor(syncBtn, "#CC555555")
        }
    }

    // ---------- Sửa vùng ----------
    private fun defaultRects(): List<RectF> {
        val (w, h) = screenSize()
        val n = paneCount()
        return (0 until n).map { i ->
            if (h >= w) RectF(40f, i * h / n + 60f, w - 40f, (i + 1) * h / n - 60f)
            else RectF(i * w / n + 60f, 40f, (i + 1) * w / n - 60f, h - 40f)
        }
    }

    private fun enterEditor() {
        if (syncOn) { syncOn = false; endStream("vao sua"); stopShizukuMirror() }
        removeCapture()
        editing = true
        val work = if (rects.isEmpty()) defaultRects() else rects.map { RectF(it) }
        log("EDITOR start rects=${work.map { rs(it) }}")
        addCapture()
        capture?.let {
            it.editing = true
            it.rects.clear(); it.rects.addAll(work)
            it.selected = if (work.isNotEmpty()) 0 else -1
            it.invalidate()
        }
        bringButtonsToTop()
        val (_, h) = screenSize()
        val add = makeButton("+ VÙNG", 20, 540) { addZone() }
        val del = makeButton("XOÁ VÙNG", 20, 660) { delZone() }
        editBtns.add(add); editBtns.add(del)
        zoneBtn?.text = "LƯU VÙNG"; setColor(zoneBtn, "#CCE08A00")
        syncBtn?.text = "HUỶ SỬA"; setColor(syncBtn, "#CCB00020")
    }

    private fun exitEditor(save: Boolean) {
        val v = capture
        if (save && v != null) {
            rects = ArrayList(v.rects.map { RectF(it) })
            saveRects()
            log("EDITOR saved rects=${rects.map { rs(it) }}")
        } else log("EDITOR cancelled")
        editing = false
        removeCapture()
        clearEditButtons()
        zoneBtn?.text = zoneLabel(); setColor(zoneBtn, "#CC555555")
        syncBtn?.text = "SYNC OFF"; setColor(syncBtn, "#CC555555")
    }

    private fun addZone() {
        val v = capture ?: return
        if (v.rects.size >= 8) return
        val (w, h) = screenSize()
        v.rects.add(RectF(w / 2 - w * 0.25f, h / 2 - h * 0.12f, w / 2 + w * 0.25f, h / 2 + h * 0.12f))
        v.selected = v.rects.size - 1
        v.invalidate()
        log("EDITOR add zone -> ${v.rects.size}")
    }

    private fun delZone() {
        val v = capture ?: return
        val i = v.selected
        if (i in v.rects.indices) {
            v.rects.removeAt(i); v.selected = -1; v.invalidate()
            log("EDITOR delete zone ${i + 1} -> ${v.rects.size}")
        }
    }

    private fun cornerPt(r: RectF, k: Int) =
        PointF(if (k == 0 || k == 3) r.left else r.right, if (k < 2) r.top else r.bottom)

    private fun onEdge(r: RectF, x: Float, y: Float): Boolean {
        val inX = x >= r.left - EDGE && x <= r.right + EDGE
        val inY = y >= r.top - EDGE && y <= r.bottom + EDGE
        return (inY && (abs(x - r.left) <= EDGE || abs(x - r.right) <= EDGE)) ||
            (inX && (abs(y - r.top) <= EDGE || abs(y - r.bottom) <= EDGE))
    }

    private fun handleEdit(v: RectView, e: MotionEvent) {
        val x = e.rawX; val y = e.rawY
        val (sw, sh) = screenSize()
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = 0; dragIdx = -1
                loop@ for (i in v.rects.indices.reversed()) {
                    val r = v.rects[i]
                    for (k in 0..3) {
                        val c = cornerPt(r, k)
                        if (abs(x - c.x) < CORNER && abs(y - c.y) < CORNER) {
                            val o = cornerPt(r, (k + 2) % 4)
                            anchorX = o.x; anchorY = o.y
                            dragMode = 2; dragIdx = i; break@loop
                        }
                    }
                    if (onEdge(r, x, y)) { dragMode = 1; dragIdx = i; break@loop }
                }
                if (dragIdx >= 0) { v.selected = dragIdx; lastX = x; lastY = y }
                log("EDIT DOWN (${x.toInt()},${y.toInt()}) zone=${dragIdx + 1} mode=${if (dragMode == 1) "move" else if (dragMode == 2) "resize" else "none"}")
                v.invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragIdx !in v.rects.indices) return
                val r = v.rects[dragIdx]
                if (dragMode == 1) {
                    var dx = x - lastX; var dy = y - lastY
                    val minDx = -r.left; val maxDx = sw - r.right
                    val minDy = -r.top; val maxDy = sh - r.bottom
                    if (minDx <= maxDx) dx = dx.coerceIn(minDx, maxDx)
                    if (minDy <= maxDy) dy = dy.coerceIn(minDy, maxDy)
                    r.offset(dx, dy)
                    lastX = x; lastY = y
                } else if (dragMode == 2) {
                    val nx = x.coerceIn(0f, sw); val ny = y.coerceIn(0f, sh)
                    val l = min(anchorX, nx); val rr = max(anchorX, nx)
                    val t = min(anchorY, ny); val b = max(anchorY, ny)
                    if (rr - l >= MIN_SIZE && b - t >= MIN_SIZE) r.set(l, t, rr, b)
                }
                v.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != 0 && dragIdx in v.rects.indices) log("EDIT UP zone=${dragIdx + 1} rect=${rs(v.rects[dragIdx])}")
                dragMode = 0
            }
        }
    }

    // ---------- Lớp phủ bắt chạm ----------
    private fun addCapture(touchable: Boolean = true) {
        val v = RectView(this).apply { setBackgroundColor(Color.parseColor("#14FF0000")) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (!touchable) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        v.setOnTouchListener { _, e ->
            if (e.pointerCount > 1) return@setOnTouchListener true
            if (editing) handleEdit(v, e) else handleSync(e)
            true
        }
        capture = v; capParams = lp
        wm.addView(v, lp)
    }

    private fun removeCapture() {
        capture?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        capture = null; capParams = null
    }

    private fun setPassThrough(on: Boolean) {
        if (systemMode || shzMirror) return          // lớp phủ đã luôn không nhận chạm
        val v = capture ?: return
        val lp = capParams ?: return
        lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                   else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    /** Danh sách vùng: dùng vùng đã cài, nếu chưa có thì chia đều màn hình. */
    private fun panes(): List<RectF> {
        if (rects.isNotEmpty()) return rects
        return defaultRects()
    }

    private fun mapPt(p: PointF, src: RectF, dst: RectF) = PointF(
        dst.left + (p.x - src.left) / src.width() * dst.width(),
        dst.top + (p.y - src.top) / src.height() * dst.height()
    )

    // =====================================================================
    // CHẾ ĐỘ HỆ THỐNG: hệ thống đưa cảm ứng thẳng cho service (không qua cửa sổ nào)
    // nên cử chỉ giả không còn huỷ ngón tay thật.
    // =====================================================================
    private fun enableSystemTouch(): Boolean {
        if (Build.VERSION.SDK_INT < 30) { log("TIC can Android 11+, dung che do ghi-roi-phat"); return false }
        return try {
            val info = serviceInfo ?: return false
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            serviceInfo = info
            val c = getTouchInteractionController(Display.DEFAULT_DISPLAY)
            val cb = object : TouchInteractionController.Callback {
                override fun onMotionEvent(event: MotionEvent) { onSystemMotion(event) }
                override fun onStateChanged(state: Int) { log("TIC state=$state") }
            }
            c.registerCallback(mainExecutor, cb)
            tic = c; ticCb = cb
            log("TIC enabled maxPointers=${c.maxPointerCount} state=${c.state}")
            true
        } catch (ex: Throwable) {
            log("TIC loi: ${ex.javaClass.simpleName}: ${ex.message}")
            false
        }
    }

    private fun disableSystemTouch() {
        if (!systemMode && tic == null) return
        try {
            ticCb?.let { tic?.unregisterCallback(it) }
            val info = serviceInfo
            if (info != null) {
                info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
                serviceInfo = info
            }
        } catch (ex: Throwable) { log("TIC tat loi: ${ex.message}") }
        tic = null; ticCb = null; systemMode = false; delegated = false
        log("TIC disabled")
    }

    private fun hitsOurUi(x: Float, y: Float): Boolean {
        val m = 30
        val loc = IntArray(2)
        for (b in listOfNotNull(syncBtn, zoneBtn)) {
            b.getLocationOnScreen(loc)
            if (x >= loc[0] - m && x <= loc[0] + b.width + m && y >= loc[1] - m && y <= loc[1] + b.height + m) return true
        }
        val (w, h) = screenSize()
        return x < 30 || x > w - 30 || y > h - 70 || y < 50     // mép màn hình: để hệ thống xử lý cử chỉ
    }

    private fun onSystemMotion(e: MotionEvent) {
        if (delegated) {
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) delegated = false
            return
        }
        if (e.action == MotionEvent.ACTION_DOWN && !streamActive && hitsOurUi(e.rawX, e.rawY)) {
            log("TIC delegate DOWN (${e.rawX.toInt()},${e.rawY.toInt()}) -> de he thong/nut xu ly")
            delegated = true
            tic?.requestDelegating()
            return
        }
        handleSync(e)
    }

    // =====================================================================
    // CHẾ ĐỘ SHIZUKU: ngón tay chạm thẳng vào game, Injector (quyền shell) đọc cảm ứng
    // thật và bơm bản sao sang các vùng khác theo thời gian thực.
    // =====================================================================
    private fun useShizuku() = prefs().getBoolean("shizuku", false)

    private fun toast(m: String) = android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()

    private fun startShizukuMirror(): Boolean {
        if (!ShizukuBridge.ready()) {
            log("Shizuku chua san sang (binder=${ShizukuBridge.binderAlive()}, quyen=${ShizukuBridge.hasPermission()})")
            toast("Shizuku chưa sẵn sàng, dùng chế độ ghi-rồi-phát")
            return false
        }
        if (rects.size < 2) {
            log("Shizuku can >= 2 vung")
            toast("Cần ít nhất 2 vùng. Bấm VÙNG để thêm.")
            return false
        }
        if (!ShizukuBridge.startInjector(this)) { toast("Không khởi động được Injector, xem log"); return false }
        val (w, h) = screenSize()
        @Suppress("DEPRECATION")
        val rot = wm.defaultDisplay.rotation
        val zs = rects.joinToString(" ") { "${it.left} ${it.top} ${it.right} ${it.bottom}" }
        val n = rects.size
        fun startCmd(path: String, mx: Int, my: Int) =
            "START $path $mx $my ${w.toInt()} ${h.toInt()} $rot $n $zs"
        val d = ShizukuBridge.devPath
        if (d != null) {
            ShizukuBridge.send(startCmd(d, ShizukuBridge.maxX, ShizukuBridge.maxY))
        } else {
            ShizukuBridge.onDev = { path, mx, my -> ShizukuBridge.send(startCmd(path, mx, my)) }
            ShizukuBridge.send("FIND")
        }
        log("Shizuku mirror yeu cau: zones=$n rot=$rot dev=$d")
        return true
    }

    private fun stopShizukuMirror() {
        if (!shzMirror) return
        ShizukuBridge.onDev = null
        ShizukuBridge.stop()
        shzMirror = false
        log("Shizuku mirror dung")
    }

    // ---------- Xử lý chạm khi SYNC ON ----------
    private fun handleSync(e: MotionEvent) {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                if (injecting || streamActive) { log("bo qua DOWN (dang phat)"); return }
                curStream = streamMode()
                log("DOWN raw=(${e.rawX.toInt()},${e.rawY.toInt()}) dev=${e.deviceId} src=${e.source} mode=${if (curStream) "realtime" else "ghi-roi-phat"}")
                if (curStream) startStream(e.rawX, e.rawY)
                else { points.clear(); downTime = e.eventTime; points.add(PointF(e.rawX, e.rawY)) }
            }
            MotionEvent.ACTION_MOVE -> {
                if (curStream) {
                    if (streamActive && !sUp) { sPending.add(PointF(e.rawX, e.rawY)); pump() }
                } else if (!injecting) points.add(PointF(e.rawX, e.rawY))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (curStream) {
                    if (streamActive && !sUp) {
                        sPending.add(PointF(e.rawX, e.rawY)); sUp = true
                        log("UP raw=(${e.rawX.toInt()},${e.rawY.toInt()}) held=${SystemClock.uptimeMillis() - sDownTime}ms")
                        pump()
                    }
                } else if (!injecting) {
                    points.add(PointF(e.rawX, e.rawY))
                    val el = e.eventTime - downTime
                    log("UP raw=(${e.rawX.toInt()},${e.rawY.toInt()}) points=${points.size} held=${el}ms")
                    replayRecorded(el)
                }
            }
        }
    }

    // =====================================================================
    // CHẾ ĐỘ REALTIME: phát từng đoạn nhỏ ngay khi ngón tay đang di chuyển
    // =====================================================================
    private fun startStream(x: Float, y: Float) {
        val list = panes()
        val idx = list.indexOfFirst { it.contains(x, y) }
        if (idx < 0) {
            val (w, h) = screenSize()
            sSrc = RectF(0f, 0f, w, h); sDst = listOf(sSrc)
        } else { sSrc = list[idx]; sDst = list }
        sStart = PointF(x, y); sLast = PointF(x, y)
        sPending.clear()
        sStrokes = arrayOfNulls(sDst.size)
        sFirst = true; sUp = false; sMoved = false; sSeg = 0
        sDownTime = SystemClock.uptimeMillis(); sLastSeg = sDownTime
        streamActive = true
        sBusy = true
        log("stream START srcIdx=$idx panes=${sDst.map { rs(it) }}")
        setPassThrough(true)
        handler.postDelayed({ sBusy = false; pump() }, if (systemMode) 0L else FIRST_DELAY)
    }

    private fun pump() {
        if (!streamActive || sBusy) return
        val now = SystemClock.uptimeMillis()
        val pts = ArrayList<PointF>()
        val finalSeg: Boolean
        val dur: Long
        if (sFirst) {
            val tap = sUp && sPending.all { abs(it.x - sStart.x) + abs(it.y - sStart.y) < 12f }
            if (tap) { finalSeg = true; dur = max(now - sDownTime, 60L).coerceAtMost(3000L); sPending.clear() }
            else { finalSeg = false; dur = 20L }
            log("first seg sau ${now - sDownTime}ms tap=$tap")
        } else {
            if (sPending.isEmpty() && !sUp) return
            pts.addAll(sPending); sPending.clear()
            finalSeg = sUp
            var d = (now - sLastSeg).coerceIn(16L, 100L)
            if (finalSeg && !sMoved && pts.size <= 1) d = max(d, 40L)
            dur = d
            if (pts.any { abs(it.x - sStart.x) + abs(it.y - sStart.y) > 12f }) sMoved = true
        }

        val builder = GestureDescription.Builder()
        for ((i, dst) in sDst.withIndex()) {
            val path = Path()
            val a = mapPt(sLast, sSrc, dst)
            path.moveTo(a.x, a.y)
            for (q in pts) { val m = mapPt(q, sSrc, dst); path.lineTo(m.x, m.y) }
            val prev = sStrokes[i]
            val st = if (prev == null) GestureDescription.StrokeDescription(path, 0, dur, !finalSeg)
                     else prev.continueStroke(path, 0, dur, !finalSeg)
            sStrokes[i] = st
            builder.addStroke(st)
        }
        if (pts.isNotEmpty()) sLast = pts.last()
        sFirst = false
        sBusy = true
        sLastSeg = now
        sSeg++
        val n = sSeg
        val ok = try {
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    sBusy = false
                    if (finalSeg) endStream("xong ($n doan)") else pump()
                }
                override fun onCancelled(g: GestureDescription?) { endStream("CANCELLED o doan $n") }
            }, null)
        } catch (ex: Exception) { log("dispatch loi: ${ex.message}"); false }
        if (!ok) endStream("dispatchGesture=false o doan $n")
    }

    private fun endStream(reason: String) {
        if (!streamActive) return
        streamActive = false
        sBusy = false
        sStrokes = arrayOfNulls(0)
        sPending.clear()
        setPassThrough(false)
        log("stream END: $reason")
    }

    // =====================================================================
    // CHẾ ĐỘ "GHI RỒI PHÁT" (dự phòng, chậm hơn nhưng ổn định)
    // =====================================================================
    private fun replayRecorded(elapsed: Long) {
        if (points.isEmpty()) return
        val list = panes()
        val first = points.first()
        val srcIdx = list.indexOfFirst { it.contains(first.x, first.y) }
        val src = list.getOrNull(srcIdx)

        val totalMove = abs(points.last().x - first.x) + abs(points.last().y - first.y)
        val isTap = totalMove < 12
        val duration = max(elapsed, 50L).coerceAtMost(10000L)
        log("replay srcIdx=$srcIdx isTap=$isTap move=${totalMove.toInt()} duration=${duration}ms")
        val builder = GestureDescription.Builder()

        fun addStroke(mapper: (PointF) -> PointF) {
            val path = Path()
            val p0 = mapper(points[0]); path.moveTo(p0.x, p0.y)
            if (!isTap) {
                val step = max(1, points.size / 40)
                var k = step
                while (k < points.size) { val q = mapper(points[k]); path.lineTo(q.x, q.y); k += step }
                val l = mapper(points.last()); path.lineTo(l.x, l.y)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        }

        if (src == null) addStroke { it }
        else list.forEach { dst -> addStroke { p -> mapPt(p, src, dst) } }

        val gesture = builder.build()
        injecting = true
        setPassThrough(true)
        handler.postDelayed({
            val t0 = SystemClock.uptimeMillis()
            fun finish(name: String) {
                log("dispatch $name sau ${SystemClock.uptimeMillis() - t0}ms")
                setPassThrough(false)
                handler.postDelayed({ injecting = false }, 120)
            }
            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { finish("COMPLETED") }
                override fun onCancelled(g: GestureDescription?) { finish("CANCELLED") }
            }, null)
            if (!ok) { setPassThrough(false); injecting = false }
        }, 40)
    }
}
