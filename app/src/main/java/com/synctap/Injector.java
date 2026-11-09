package com.synctap;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chạy trong tiến trình quyền shell (app_process) do Shizuku khởi động.
 * Đọc cảm ứng thật từ /dev/input, rồi bơm bản sao sang các vùng khác bằng InputManager.
 * Lệnh nhận qua stdin: PING, FIND, TAP x y, START ..., STOP.
 */
public class Injector {
    private static final int SLOTS = 10;
    private static Object inputManager;
    private static Method injectMethod;
    private static Method setDisplayId;
    private static final Object lock = new Object();
    private static final LinkedHashMap<Integer, float[]> inj = new LinkedHashMap<>();
    private static long injDownTime = 0;
    private static int errCount = 0;

    private static volatile boolean running = false;
    private static float[][] zones = new float[0][];
    private static int W, H, rot;
    private static float maxX, maxY;
    private static int curSlot = 0;
    private static final boolean[] active = new boolean[SLOTS];
    private static final boolean[] newDown = new boolean[SLOTS];
    private static final int[] rx = new int[SLOTS];
    private static final int[] ry = new int[SLOTS];
    private static final int[] srcZone = new int[SLOTS];

    static void log(String s) {
        System.out.println("LOG " + s);
        System.out.flush();
    }

    public static void main(String[] args) {
        try {
            initInput();
            log("init ok uid=" + android.os.Process.myUid());
        } catch (Throwable t) {
            log("init loi: " + t);
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handle(line.trim());
                } catch (Throwable t) {
                    log("lenh loi '" + line + "': " + t);
                }
            }
        } catch (Throwable t) {
            // stdin đóng
        }
        shutdown();
    }

    private static void shutdown() {
        running = false;
        try {
            syncInjected(new LinkedHashMap<Integer, float[]>());
        } catch (Throwable t) {
            // bỏ qua
        }
        log("thoat");
        System.exit(0);
    }

    private static void initInput() throws Exception {
        Class<?> imc = Class.forName("android.hardware.input.InputManager");
        Method getInstance = imc.getDeclaredMethod("getInstance");
        inputManager = getInstance.invoke(null);
        injectMethod = imc.getMethod("injectInputEvent", InputEvent.class, int.class);
        try {
            setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (Throwable t) {
            setDisplayId = null;
        }
    }

    private static void handle(String line) throws Exception {
        if (line.isEmpty()) return;
        String[] a = line.split("\\s+");
        switch (a[0]) {
            case "PING":
                log("pong");
                break;
            case "FIND":
                find();
                break;
            case "TAP":
                tap(Float.parseFloat(a[1]), Float.parseFloat(a[2]));
                break;
            case "START":
                start(a);
                break;
            case "STOP":
                shutdown();
                break;
            default:
                log("lenh la: " + line);
        }
    }

    // ---------- Tìm thiết bị cảm ứng ----------
    private static int parseMax(String line) {
        Matcher m = Pattern.compile("max\\s+(\\d+)").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static void find() throws Exception {
        Process p = new ProcessBuilder("getevent", "-pl").redirectErrorStream(true).start();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String dev = null;
        int mx = -1, my = -1;
        boolean slot = false;
        String best = null;
        int bx = 0, by = 0;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("add device")) {
                if (dev != null && mx > 0 && my > 0 && slot && best == null) {
                    best = dev; bx = mx; by = my;
                }
                int i = line.indexOf("/dev/input/");
                dev = i >= 0 ? line.substring(i).trim() : null;
                mx = -1; my = -1; slot = false;
            } else if (line.contains("ABS_MT_SLOT")) {
                slot = true;
            } else if (line.contains("ABS_MT_POSITION_X")) {
                mx = parseMax(line);
            } else if (line.contains("ABS_MT_POSITION_Y")) {
                my = parseMax(line);
            }
        }
        if (dev != null && mx > 0 && my > 0 && slot && best == null) {
            best = dev; bx = mx; by = my;
        }
        if (best == null) {
            log("khong tim thay man cam ung");
        } else {
            System.out.println("DEV " + best + " " + bx + " " + by);
            System.out.flush();
        }
    }

    // ---------- Bơm sự kiện ----------
    private static int indexOf(int pid) {
        int i = 0;
        for (Integer k : inj.keySet()) {
            if (k == pid) return i;
            i++;
        }
        return -1;
    }

    private static void send(int action, int idx) {
        int n = inj.size();
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[n];
        int i = 0;
        for (Map.Entry<Integer, float[]> e : inj.entrySet()) {
            pp[i] = new MotionEvent.PointerProperties();
            pp[i].id = e.getKey();
            pp[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pc[i] = new MotionEvent.PointerCoords();
            pc[i].x = e.getValue()[0];
            pc[i].y = e.getValue()[1];
            pc[i].pressure = 1f;
            pc[i].size = 1f;
            i++;
        }
        int act = action;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            act = action | (idx << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(injDownTime, now, act, n, pp, pc, 0, 0, 1f, 1f,
                -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            if (setDisplayId != null) setDisplayId.invoke(ev, 0);
            injectMethod.invoke(inputManager, ev, 0);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            if (errCount++ < 5) log("inject loi: " + c);
        }
        ev.recycle();
    }

    private static void syncInjected(Map<Integer, float[]> target) {
        synchronized (lock) {
            for (Integer pid : new ArrayList<Integer>(inj.keySet())) {
                if (!target.containsKey(pid)) {
                    int idx = indexOf(pid);
                    if (inj.size() == 1) send(MotionEvent.ACTION_UP, 0);
                    else send(MotionEvent.ACTION_POINTER_UP, idx);
                    inj.remove(pid);
                }
            }
            for (Map.Entry<Integer, float[]> e : target.entrySet()) {
                if (!inj.containsKey(e.getKey())) {
                    inj.put(e.getKey(), e.getValue().clone());
                    if (inj.size() == 1) {
                        injDownTime = SystemClock.uptimeMillis();
                        send(MotionEvent.ACTION_DOWN, 0);
                    } else {
                        send(MotionEvent.ACTION_POINTER_DOWN, indexOf(e.getKey()));
                    }
                }
            }
            boolean moved = false;
            for (Map.Entry<Integer, float[]> e : target.entrySet()) {
                float[] cur = inj.get(e.getKey());
                float[] nv = e.getValue();
                if (cur != null && (cur[0] != nv[0] || cur[1] != nv[1])) {
                    cur[0] = nv[0];
                    cur[1] = nv[1];
                    moved = true;
                }
            }
            if (moved) send(MotionEvent.ACTION_MOVE, 0);
        }
    }

    private static void tap(float x, float y) throws Exception {
        LinkedHashMap<Integer, float[]> t = new LinkedHashMap<Integer, float[]>();
        t.put(0, new float[]{x, y});
        syncInjected(t);
        Thread.sleep(60);
        syncInjected(new LinkedHashMap<Integer, float[]>());
        log("tap xong tai " + (int) x + "," + (int) y);
    }

    // ---------- Phản chiếu cảm ứng ----------
    private static void start(String[] a) {
        String path = a[1];
        maxX = Float.parseFloat(a[2]);
        maxY = Float.parseFloat(a[3]);
        W = Integer.parseInt(a[4]);
        H = Integer.parseInt(a[5]);
        rot = Integer.parseInt(a[6]);
        int n = Integer.parseInt(a[7]);
        zones = new float[n][4];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 4; k++) zones[i][k] = Float.parseFloat(a[8 + i * 4 + k]);
        }
        if (running) {
            log("da chay roi");
            return;
        }
        running = true;
        log("START dev=" + path + " max=" + (int) maxX + "x" + (int) maxY + " screen=" + W + "x" + H
                + " rot=" + rot + " zones=" + n);
        final String devPath = path;
        new Thread(new Runnable() {
            public void run() {
                mirrorLoop(devPath);
            }
        }, "mirror").start();
    }

    private static float[] toPx(int rawX, int rawY) {
        float nx = maxX > 0 ? rawX / maxX : 0f;
        float ny = maxY > 0 ? rawY / maxY : 0f;
        switch (rot) {
            case 1: return new float[]{ny * W, (1 - nx) * H};
            case 2: return new float[]{(1 - nx) * W, (1 - ny) * H};
            case 3: return new float[]{(1 - ny) * W, nx * H};
            default: return new float[]{nx * W, ny * H};
        }
    }

    private static int findZone(float x, float y) {
        for (int i = zones.length - 1; i >= 0; i--) {
            float[] z = zones[i];
            if (x >= z[0] && x <= z[2] && y >= z[1] && y <= z[3]) return i;
        }
        return -1;
    }

    private static void mirrorLoop(String path) {
        int evSize = android.os.Process.is64Bit() ? 24 : 16;
        for (int i = 0; i < SLOTS; i++) srcZone[i] = -1;
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] buf = new byte[evSize];
            ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            int frames = 0;
            while (running) {
                int got = 0;
                while (got < evSize) {
                    int r = fis.read(buf, got, evSize - got);
                    if (r < 0) throw new EOFException();
                    got += r;
                }
                int off = evSize - 8;
                int type = bb.getShort(off) & 0xffff;
                int code = bb.getShort(off + 2) & 0xffff;
                int value = bb.getInt(off + 4);
                if (type == 3) {                       // EV_ABS
                    if (code == 0x2f) {                // MT_SLOT
                        if (value >= 0 && value < SLOTS) curSlot = value;
                    } else if (code == 0x39) {         // MT_TRACKING_ID
                        if (value == -1) {
                            active[curSlot] = false;
                        } else {
                            active[curSlot] = true;
                            newDown[curSlot] = true;
                        }
                    } else if (code == 0x35) {
                        rx[curSlot] = value;
                    } else if (code == 0x36) {
                        ry[curSlot] = value;
                    }
                } else if (type == 0 && code == 0) {   // SYN_REPORT
                    processFrame();
                    if (frames++ < 3) log("nhan duoc su kien cam ung that");
                }
            }
        } catch (Throwable t) {
            log("mirror loi: " + t);
        }
        running = false;
    }

    private static void processFrame() {
        LinkedHashMap<Integer, float[]> target = new LinkedHashMap<Integer, float[]>();
        for (int s = 0; s < SLOTS; s++) {
            if (!active[s]) {
                srcZone[s] = -1;
                continue;
            }
            float[] p = toPx(rx[s], ry[s]);
            if (newDown[s]) {
                srcZone[s] = findZone(p[0], p[1]);
                newDown[s] = false;
            }
            int sz = srcZone[s];
            if (sz < 0 || s >= 4) continue;
            float[] a = zones[sz];
            float aw = a[2] - a[0], ah = a[3] - a[1];
            if (aw <= 0 || ah <= 0) continue;
            for (int d = 0; d < zones.length; d++) {
                if (d == sz) continue;
                float[] z = zones[d];
                float x = z[0] + (p[0] - a[0]) / aw * (z[2] - z[0]);
                float y = z[1] + (p[1] - a[1]) / ah * (z[3] - z[1]);
                x = Math.max(0, Math.min(W - 1, x));
                y = Math.max(0, Math.min(H - 1, y));
                target.put(s * 8 + d, new float[]{x, y});
            }
        }
        syncInjected(target);
    }
}
