package com.cloudphone.clicker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 坐标拾取工具（前台服务）。
 * - 全屏透明触摸层：点屏幕任意位置，顶部提示条显示精确 X/Y
 * - 顶部有「退出拾取」按钮和坐标显示
 * 仅用于取坐标，不会真正触发游戏（拾取时游戏收不到点击）。
 */
public class PickService extends Service {
    public static final String ACTION_START = "com.cloudphone.clicker.PICK_START";
    public static final String ACTION_STOP = "com.cloudphone.clicker.PICK_STOP";
    private static final String CHANNEL_ID = "cloudclicker_pick";
    private static final int NOTI_ID = 1002;

    private WindowManager wm;
    private View touchLayer;
    private View topBar;
    private TextView tvCoord;
    private boolean added = false;

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        startForeground(NOTI_ID, buildNotification());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            removeViews();
            stopSelf();
            return START_NOT_STICKY;
        }
        showViews();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        removeViews();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "坐标拾取",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("坐标拾取工具运行中");
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
                .setContentText("坐标拾取中 · 点屏幕取坐标")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private int overlayType() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void showViews() {
        if (added) return;
        if (!Settings.canDrawOverlays(this)) {
            toast("请先给悬浮窗权限");
            return;
        }

        // ---- 全屏透明触摸层：点哪里显示哪里坐标
        touchLayer = new View(this);
        touchLayer.setBackgroundColor(Color.TRANSPARENT);
        WindowManager.LayoutParams touchLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        touchLp.gravity = Gravity.TOP | Gravity.START;
        touchLayer.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                int x = (int) ev.getRawX();
                int y = (int) ev.getRawY();
                if (tvCoord != null) tvCoord.setText("坐标  X=" + x + "  ,  Y=" + y);
                toast("X=" + x + "  Y=" + y + "（记下来填进 App）");
            }
            return true; // 消费触摸，不让事件传到游戏
        });

        // ---- 顶部提示条：显示坐标 + 退出按钮
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Color.rgb(30, 30, 40));
        bar.setPadding(dp(10), dp(8), dp(10), dp(8));
        tvCoord = new TextView(this);
        tvCoord.setText("坐标拾取中：点游戏按钮取坐标");
        tvCoord.setTextColor(Color.WHITE);
        tvCoord.setTextSize(15);
        bar.addView(tvCoord);
        TextView hint = new TextView(this);
        hint.setText("取到坐标后记下来，点下面按钮退出，回 App 填进动作点");
        hint.setTextColor(Color.rgb(200, 200, 200));
        hint.setTextSize(11);
        bar.addView(hint);
        Button btnExit = new Button(this);
        btnExit.setText("退出拾取");
        btnExit.setBackgroundColor(Color.rgb(200, 52, 52));
        btnExit.setTextColor(Color.WHITE);
        btnExit.setOnClickListener(v -> {
            stopSelf();
        });
        bar.addView(btnExit);

        WindowManager.LayoutParams barLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        barLp.gravity = Gravity.TOP | Gravity.START;
        topBar = bar;

        try {
            wm.addView(touchLayer, touchLp);
            wm.addView(topBar, barLp);
            added = true;
        } catch (Exception e) {
            toast("拾取层添加失败：" + e.getMessage());
            removeViews();
        }
    }

    private void removeViews() {
        if (touchLayer != null) {
            try { wm.removeView(touchLayer); } catch (Exception ignored) {}
        }
        if (topBar != null) {
            try { wm.removeView(topBar); } catch (Exception ignored) {}
        }
        touchLayer = null;
        topBar = null;
        tvCoord = null;
        added = false;
    }

    private void toast(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
