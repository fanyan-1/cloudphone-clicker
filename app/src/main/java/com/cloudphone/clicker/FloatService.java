package com.cloudphone.clicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 游戏内悬浮控制球（前台服务）。
 * - 屏幕上一个小圆球：绿=运行中，红=已停止
 * - 点一下：开始 / 停止 切换（无需切回 App）
 * - 按住拖动：随意移动到不挡游戏的位置
 */
public class FloatService extends Service {
    public static final String ACTION_SHOW = "com.cloudphone.clicker.FLOAT_SHOW";
    public static final String ACTION_HIDE = "com.cloudphone.clicker.FLOAT_HIDE";
    public static final String CHANNEL_ID = "cloudclicker_float";
    private static final int NOTI_ID = 1001;

    private WindowManager wm;
    private View ball;
    private WindowManager.LayoutParams lp;
    private boolean added = false;
    private volatile boolean engineRunning = false;

    private float downX, downY;
    private int lpX, lpY;
    private boolean moved;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ClickerService.ACTION_STATUS.equals(i.getAction())) {
                engineRunning = i.getBooleanExtra("running", false);
                paint();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        startForeground(NOTI_ID, buildNotification());
        IntentFilter f = new IntentFilter(ClickerService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, f);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? "" : intent.getAction();
        if (ACTION_HIDE.equals(a)) {
            removeBall();
            stopSelf();
            return START_NOT_STICKY;
        }
        showBall();
        // 主动请求一次当前状态，同步球的颜色
        requestStatus();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        removeBall();
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "悬浮控制球",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("保持悬浮控制球常驻");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("云手机点击精灵")
                .setContentText("悬浮控制球运行中 · 点球开始/停止")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void showBall() {
        if (added || ball != null) return;
        if (!Settings.canDrawOverlays(this)) {
            toast("请先给悬浮窗权限");
            return;
        }
        TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(18);
        tv.setGravity(Gravity.CENTER);
        ball = tv;
        paint();

        int size = dp(56);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(110);

        ball.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    lpX = lp.x;
                    lpY = lp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                    lp.x = (int) (lpX + dx);
                    lp.y = (int) (lpY + dy);
                    try { wm.updateViewLayout(ball, lp); } catch (Exception ignored) {}
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved) toggleEngine();
                    return true;
                default:
                    return true;
            }
        });

        try {
            wm.addView(ball, lp);
            added = true;
        } catch (Exception e) {
            toast("悬浮球添加失败：" + e.getMessage());
            ball = null;
        }
    }

    private void paint() {
        if (ball == null) return;
        TextView tv = (TextView) ball;
        tv.setText(engineRunning ? "■" : "▶");
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(engineRunning ? Color.rgb(26, 160, 90) : Color.rgb(200, 52, 52));
        gd.setStroke(2, Color.WHITE);
        tv.setBackground(gd);
    }

    private void toggleEngine() {
        if (!accessOn()) {
            toast("请先开启无障碍权限");
            return;
        }
        Intent it = new Intent(this, ClickerService.class);
        it.setAction(engineRunning ? ClickerService.ACTION_STOP : ClickerService.ACTION_START);
        try { startService(it); } catch (Exception e) { toast("启动失败：" + e.getMessage()); }
        // 引擎可能因未配置动作而未真正运行，延迟回读一次状态校正球色
        if (ball != null) {
            ball.postDelayed(this::requestStatus, 400);
        }
    }

    private void requestStatus() {
        try {
            startService(new Intent(this, ClickerService.class).setAction(ClickerService.ACTION_STATUS));
        } catch (Exception ignored) {}
    }

    private boolean accessOn() {
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            String pkg = getPackageName().toLowerCase();
            for (String part : flat.split(":")) {
                String p = part.trim().toLowerCase();
                if (p.contains(pkg) && p.contains("clickerservice")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void removeBall() {
        if (added && ball != null) {
            try { wm.removeView(ball); } catch (Exception ignored) {}
        }
        added = false;
        ball = null;
    }

    private void toast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
