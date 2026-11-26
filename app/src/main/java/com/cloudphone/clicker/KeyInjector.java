package com.cloudphone.clicker;

import android.hardware.input.InputManager;
import android.view.InputEvent;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 键盘事件注入引擎（多方案自动降级）。
 *
 * 安卓发“真实键盘按键”没有公开 API，只有两条路：
 *  1) 反射 InputManager.injectInputEvent —— 能发真正的 KeyEvent down/up，
 *     但普通 App 需要 INJECT_EVENTS 权限，多数系统会拒绝；部分定制 ROM/系统签名可用。
 *  2) Root Shell：su -c "input keyevent KEYCODE_xxx" —— 云手机多数自带 root，
 *     可发出被游戏识别的按键；组合键用多条 keyevent 快速连发。
 *
 * detect() 会按顺序探测，结果缓存在 mode；界面显示状态文本。
 */
public final class KeyInjector {
    public static final int MODE_UNKNOWN = -1;
    public static final int MODE_NONE = 0;
    public static final int MODE_REFLECT = 1;
    public static final int MODE_ROOT = 2;

    // 可选择的按键名（不含修饰键，修饰键单独勾选）
    public static final String[] KEY_NAMES = {
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
            "空格", "回车", "Tab", "Esc", "退格", "删除",
            "↑", "↓", "←", "→", "Home", "End", "PageUp", "PageDown", "Insert"
    };

    private static final Map<String, Integer> CODES = new LinkedHashMap<>();
    static {
        for (char c = 'A'; c <= 'Z'; c++) CODES.put(String.valueOf(c), KeyEvent.KEYCODE_A + (c - 'A'));
        for (char d = '0'; d <= '9'; d++) CODES.put(String.valueOf(d), KeyEvent.KEYCODE_0 + (d - '0'));
        for (int f = 1; f <= 12; f++) CODES.put("F" + f, KeyEvent.KEYCODE_F1 + (f - 1));
        CODES.put("空格", KeyEvent.KEYCODE_SPACE);
        CODES.put("回车", KeyEvent.KEYCODE_ENTER);
        CODES.put("Tab", KeyEvent.KEYCODE_TAB);
        CODES.put("Esc", KeyEvent.KEYCODE_ESCAPE);
        CODES.put("退格", KeyEvent.KEYCODE_DEL);
        CODES.put("删除", KeyEvent.KEYCODE_FORWARD_DEL);
        CODES.put("↑", KeyEvent.KEYCODE_DPAD_UP);
        CODES.put("↓", KeyEvent.KEYCODE_DPAD_DOWN);
        CODES.put("←", KeyEvent.KEYCODE_DPAD_LEFT);
        CODES.put("→", KeyEvent.KEYCODE_DPAD_RIGHT);
        CODES.put("Home", KeyEvent.KEYCODE_MOVE_HOME);
        CODES.put("End", KeyEvent.KEYCODE_MOVE_END);
        CODES.put("PageUp", KeyEvent.KEYCODE_PAGE_UP);
        CODES.put("PageDown", KeyEvent.KEYCODE_PAGE_DOWN);
        CODES.put("Insert", KeyEvent.KEYCODE_INSERT);
        CODES.put("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT);
        CODES.put("Alt", KeyEvent.KEYCODE_ALT_LEFT);
        CODES.put("Shift", KeyEvent.KEYCODE_SHIFT_LEFT);
    }

    public static int codeOf(String name) {
        if (name == null) return -1;
        Integer v = CODES.get(name.trim());
        return v == null ? -1 : v;
    }

    private static int mode = MODE_UNKNOWN;
    private static String detail = "未检测";
    private static Method injectMethod = null;
    private static Object inputManager = null;

    /** 检测注入通道，返回实际模式 */
    public static synchronized int detect() {
        if (mode != MODE_UNKNOWN) return mode;

        // 1) 反射注入：发一个 KEYCODE_UNKNOWN 探测是否有权限
        try {
            Method getInstance = InputManager.class.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object im = getInstance.invoke(null);
            Method inj = InputManager.class.getDeclaredMethod(
                    "injectInputEvent", InputEvent.class, int.class);
            inj.setAccessible(true);
            long now = System.currentTimeMillis();
            KeyEvent probe = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_UNKNOWN, 0);
            Object r = inj.invoke(im, probe, 0);
            if (r instanceof Boolean && (Boolean) r) {
                injectMethod = inj;
                inputManager = im;
                mode = MODE_REFLECT;
                detail = "系统注入（无需 Root）";
                return mode;
            }
        } catch (Throwable t) {
            detail = "反射不可用：" + shortErr(t);
        }

        // 2) Root Shell
        if (hasRoot()) {
            mode = MODE_ROOT;
            detail = "Root 注入（input keyevent）";
            return mode;
        }

        mode = MODE_NONE;
        if (detail.startsWith("反射不可用")) {
            detail = detail + "；且无 Root —— 按键功能不可用（需 Root 或系统签名）";
        } else {
            detail = "无 Root 且无系统注入权限 —— 按键功能不可用";
        }
        return mode;
    }

    public static int mode() { return detect(); }

    public static String statusText() {
        detect();
        if (mode == MODE_REFLECT) return "按键引擎：系统注入 ✓（无需 Root）";
        if (mode == MODE_ROOT) return "按键引擎：Root 注入 ✓";
        return "按键引擎：不可用（需 Root）";
    }

    public static String detailText() { detect(); return detail; }

    public static boolean available() {
        int m = detect();
        return m == MODE_REFLECT || m == MODE_ROOT;
    }

    private static boolean hasRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return line != null && line.contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 发送一组按键。codes 顺序：[修饰键..., 主键] */
    public static boolean send(int[] codes, int holdMs, boolean simultaneous) {
        if (codes == null || codes.length == 0) return false;
        int m = detect();
        if (m == MODE_REFLECT) return sendReflect(codes, holdMs, simultaneous);
        if (m == MODE_ROOT) return sendRoot(codes, holdMs, simultaneous);
        return false;
    }

    private static boolean sendReflect(int[] codes, int holdMs, boolean simultaneous) {
        try {
            long now = System.currentTimeMillis();
            if (simultaneous) {
                for (int c : codes) inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, c, 0));
                sleep(Math.max(1, holdMs));
                for (int i = codes.length - 1; i >= 0; i--) {
                    inject(new KeyEvent(System.currentTimeMillis(), System.currentTimeMillis(),
                            KeyEvent.ACTION_UP, codes[i], 0));
                }
            } else {
                for (int c : codes) {
                    long t = System.currentTimeMillis();
                    inject(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, c, 0));
                    sleep(Math.max(1, holdMs));
                    inject(new KeyEvent(System.currentTimeMillis(), System.currentTimeMillis(),
                            KeyEvent.ACTION_UP, c, 0));
                }
            }
            return true;
        } catch (Throwable t) {
            detail = "注入失败：" + shortErr(t);
            return false;
        }
    }

    private static void inject(KeyEvent ev) throws Exception {
        Object r = injectMethod.invoke(inputManager, ev, 0);
        if (!(r instanceof Boolean) || !(Boolean) r) {
            throw new IllegalStateException("injectInputEvent returned false");
        }
    }

    private static boolean sendRoot(int[] codes, int holdMs, boolean simultaneous) {
        try {
            StringBuilder cmd = new StringBuilder("input keyevent");
            if (holdMs > 600) cmd.append(" --longpress");
            for (int c : codes) cmd.append(' ').append(KeyEvent.keyCodeToString(c));
            if (!simultaneous) {
                // 依次：一条命令内 input 会逐个 down+up，天然是顺序按键
                return rootExec(cmd.toString());
            }
            // 同时：修饰键用长按压后台保持，主键立刻发出（尽量模拟组合键）
            if (codes.length > 1) {
                StringBuilder all = new StringBuilder("input keyevent");
                for (int c : codes) all.append(' ').append(KeyEvent.keyCodeToString(c));
                return rootExec(all.toString());
            }
            if (holdMs > 600) {
                return rootExec("input keyevent --longpress " + KeyEvent.keyCodeToString(codes[0]));
            }
            return rootExec("input keyevent " + KeyEvent.keyCodeToString(codes[0]));
        } catch (Throwable t) {
            detail = "Root 注入失败：" + shortErr(t);
            return false;
        }
    }

    private static boolean rootExec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            detail = "su 执行失败：" + shortErr(t);
            return false;
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String shortErr(Throwable t) {
        String s = t.toString();
        return s.length() > 90 ? s.substring(0, 90) : s;
    }
}
