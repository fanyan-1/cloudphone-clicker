package com.cloudphone.clicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无障碍点击引擎：用 dispatchGesture 模拟真实触摸。
 * - 依次模式：动作逐个发，每个之间等 actionIntervalMs
 * - 同时模式：一组内所有轻点动作合并成一个多点手势，同时按下
 * - 一轮(所有启用组)跑完后额外等 cycleGapMs
 */
public class ClickerService extends AccessibilityService {
    public static final String TAG = "CloudClicker";
    public static final String ACTION_START = "com.cloudphone.clicker.START";
    public static final String ACTION_STOP = "com.cloudphone.clicker.STOP";
    public static final String ACTION_STATUS = "com.cloudphone.clicker.STATUS";

    // 注意：AccessibilityService.onBind 是 final，不能 override。
    // Activity 通过 startService/stopService 控制，用静态回调拿状态。

    private volatile boolean running = false;
    private final AtomicBoolean cycleBusy = new AtomicBoolean(false);
    private Thread worker;
    private Handler main;
    private ClickConfig cfg;
    private int seqIdx = 0;        // 下一组起点
    private int pickedInCycle = 0; // 本轮已发组数
    private long actionCount = 0;
    private long startTime = 0;

    /** 供界面查询状态 */
    public interface StatusCallback {
        void onStatus(boolean running, long count, long elapsedSec);
    }
    private static volatile StatusCallback callback;
    public static void setCallback(StatusCallback cb) { callback = cb; }

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
    }

    @SuppressLint("AccessibilityPolicy")
    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRunning();
        } else if (intent != null && ACTION_START.equals(intent.getAction())) {
            startRunning();
        } else if (intent != null && ACTION_STATUS.equals(intent.getAction())) {
            report();   // Activity 请求刷新状态
        }
        return START_STICKY;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* 不需要监听事件 */ }

    @Override
    public void onInterrupt() { stopRunning(); }

    @Override
    public void onDestroy() {
        stopRunning();
        super.onDestroy();
    }

    public boolean isRunning() { return running; }

    public void startRunning() {
        if (running) return;
        cfg = ClickConfig.load(this);
        if (cfg.usableGroupCount() == 0) {
            toast("请至少启用一组并添加一个动作点");
            return;
        }
        running = true;
        seqIdx = 0;
        pickedInCycle = 0;
        actionCount = 0;
        startTime = System.currentTimeMillis();
        worker = new Thread(this::loop, "clicker-worker");
        worker.setPriority(Thread.NORM_PRIORITY + 1);
        worker.start();
        toast("已开始运行");
        report();
    }

    public void stopRunning() {
        if (!running) return;
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            try { w.interrupt(); } catch (Exception ignored) {}
        }
        toast("已停止");
        report();
    }

    private void loop() {
        try {
            while (running) {
                if (outOfLimit()) break;

                int pick = nextUsableGroup();
                if (pick < 0) break;

                ClickConfig.Group group = cfg.groups[pick];

                if (cfg.orderSimultaneous) {
                    dispatchSimultaneous(group.actions);
                    actionCount += group.actions.size();
                } else {
                    for (ClickConfig.Action a : group.actions) {
                        if (!running) break;
                        dispatchOne(a);
                        actionCount++;
                        sleep(cfg.actionIntervalMs);
                    }
                }

                // 本轮进度
                pickedInCycle++;
                if (pickedInCycle >= cfg.usableGroupCount()) {
                    pickedInCycle = 0;
                    sleep(cfg.cycleGapMs);   // 一轮跑完后的额外等待
                }
                if (!cfg.orderSimultaneous) {
                    // 组间也用同一间隔（发下一组前）
                    sleep(cfg.actionIntervalMs);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "loop error", e);
        } finally {
            running = false;
            report();
        }
    }

    /** 顺序轮询：从 seqIdx 起找下一个启用组 */
    private int nextUsableGroup() {
        int n = ClickConfig.GROUPS;
        for (int k = 0; k < n; k++) {
            int i = (seqIdx + k) % n;
            if (cfg.groupUsable(i)) {
                seqIdx = (i + 1) % n;
                return i;
            }
        }
        return -1;
    }

    private boolean outOfLimit() {
        if (cfg.maxActions > 0 && actionCount >= cfg.maxActions) return true;
        if (cfg.maxMinutes > 0 &&
                System.currentTimeMillis() - startTime >= (long) cfg.maxMinutes * 60_000L) return true;
        return false;
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** 等待手势执行完成（避免上一个还没按下就发下一个） */
    private void awaitGesture() {
        long deadline = System.currentTimeMillis() + 3000;
        while (cycleBusy.get() && running && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** 依次模式的单个动作：轻点 / 长按 / 滑动 */
    private void dispatchOne(ClickConfig.Action a) {
        if (a == null) return;
        if (a.type == ClickConfig.ACT_SWIPE) {
            dispatchSwipe(a);
        } else {
            long hold = (a.type == ClickConfig.ACT_LONG)
                    ? Math.max(1, a.holdMs) : 40;
            dispatchTap(a.x, a.y, hold);
        }
        awaitGesture();
    }

    /** 同时模式：把组内所有"轻点"合并成一个多点手势 */
    private void dispatchSimultaneous(List<ClickConfig.Action> actions) {
        if (actions == null || actions.isEmpty()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // 低版本不支持多指，退化为依次
            for (ClickConfig.Action a : actions) dispatchOne(a);
            return;
        }
        GestureDescription.Builder b = new GestureDescription.Builder();
        int added = 0;
        for (ClickConfig.Action a : actions) {
            if (a == null || a.type == ClickConfig.ACT_SWIPE) continue; // 滑动不参与多点
            long hold = (a.type == ClickConfig.ACT_LONG) ? Math.max(1, a.holdMs) : 40;
            Path p = new Path();
            p.moveTo(a.x, a.y);
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, hold, true));
            added++;
        }
        if (added == 0) return;
        cycleBusy.set(true);
        final GestureDescription gd = b.build();
        main.post(() -> {
            try {
                dispatchGesture(gd, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription d) { cycleBusy.set(false); }
                    @Override public void onCancelled(GestureDescription d) { cycleBusy.set(false); }
                }, main);
            } catch (Exception e) {
                cycleBusy.set(false);
                Log.e(TAG, "dispatchGesture simultaneous failed", e);
            }
        });
        awaitGesture();
    }

    private void dispatchTap(int x, int y, long holdMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, holdMs))
                .build();
        cycleBusy.set(true);
        main.post(() -> {
            try {
                dispatchGesture(gd, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription d) { cycleBusy.set(false); }
                    @Override public void onCancelled(GestureDescription d) { cycleBusy.set(false); }
                }, main);
            } catch (Exception e) {
                cycleBusy.set(false);
                Log.e(TAG, "dispatchGesture tap failed", e);
            }
        });
    }

    private void dispatchSwipe(ClickConfig.Action a) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        Path p = new Path();
        p.moveTo(a.x, a.y);
        p.lineTo(a.x2, a.y2);
        GestureDescription gd = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(50, a.swipeMs)))
                .build();
        cycleBusy.set(true);
        main.post(() -> {
            try {
                dispatchGesture(gd, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription d) { cycleBusy.set(false); }
                    @Override public void onCancelled(GestureDescription d) { cycleBusy.set(false); }
                }, main);
            } catch (Exception e) {
                cycleBusy.set(false);
                Log.e(TAG, "dispatchGesture swipe failed", e);
            }
        });
    }

    private void report() {
        final boolean r = running;
        final long c = actionCount;
        final long sec = startTime > 0 ? (System.currentTimeMillis() - startTime) / 1000 : 0;
        if (callback != null) main.post(() -> callback.onStatus(r, c, sec));
        // 同时发广播，Activity 未绑定也能收到
        try {
            android.content.Intent it = new android.content.Intent(ACTION_STATUS);
            it.putExtra("running", r);
            it.putExtra("count", c);
            it.putExtra("elapsed", sec);
            sendBroadcast(it);
        } catch (Exception ignored) {}
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show());
    }
}
