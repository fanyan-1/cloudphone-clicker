package com.cloudphone.clicker;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 设置界面（代码构建，避免复杂布局 XML 出错） */
public class MainActivity extends Activity implements ClickerService.StatusCallback {

    private ClickConfig cfg;
    private TextView tvStatus, tvCount;
    private Button btnStart;
    private LinearLayout groupsBox;
    private CheckBox chkSimultaneous;
    private EditText edActionInterval, edCycleGap, edHold, edMaxMin, edMaxActions;

    private ClickerService service;
    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((ClickerService.LocalBinder) b).getService();
            ClickerService.setCallback(MainActivity.this);
            refreshStatus();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = ClickConfig.load(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setContentView(sv);

        // ---- 标题
        TextView title = txt("云手机点击精灵", 20, true);
        title.setTextColor(Color.rgb(43, 87, 154));
        root.addView(title);
        root.addView(txt("把电脑版的按键调度搬到云手机：多组动作循环执行", 12, false));

        // ---- 无障碍权限
        tvStatus = txt("", 13, true);
        root.addView(tvStatus);
        Button btnPerm = btn("开启无障碍权限（必须）");
        btnPerm.setOnClickListener(v -> openAccessibility());
        root.addView(btnPerm);

        // ---- 状态
        tvCount = txt("已执行 0 次 · 运行 0 秒", 13, false);
        root.addView(tvCount);

        // ---- 运行按钮
        btnStart = btn("开始运行");
        btnStart.setBackgroundColor(Color.rgb(26, 115, 232));
        btnStart.setTextColor(Color.WHITE);
        btnStart.setOnClickListener(v -> toggleRun());
        root.addView(btnStart);

        // ---- 组合方式
        chkSimultaneous = new CheckBox(this);
        chkSimultaneous.setText("同时按下（勾选=一组内多点同时；不勾=依次逐个点）");
        chkSimultaneous.setChecked(cfg.orderSimultaneous);
        root.addView(chkSimultaneous);

        // ---- 调度参数
        root.addView(section("调度设置"));
        edActionInterval = numField(root, "动作间隔(毫秒)", cfg.actionIntervalMs);
        edCycleGap = numField(root, "循环间隔(毫秒，一轮跑完后等待)", cfg.cycleGapMs);
        edHold = numField(root, "长按时长(毫秒)", cfg.longHoldMs);
        edMaxMin = numField(root, "时长上限(分钟，0=不限)", cfg.maxMinutes);
        edMaxActions = numField(root, "次数上限(0=不限)", cfg.maxActions);

        // ---- 5 组动作
        root.addView(section("动作序列（1→5 循环，未启用的组跳过）"));
        groupsBox = new LinearLayout(this);
        groupsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(groupsBox);
        buildGroups();

        // ---- 保存
        Button btnSave = btn("保存设置");
        btnSave.setOnClickListener(v -> { collect(); cfg.save(this); toast("已保存"); });
        root.addView(btnSave);

        root.addView(txt("提示：坐标是云手机屏幕的绝对像素。长按=按住指定毫秒；滑动=从起点滑到终点。", 11, false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccessStatus();
        bindService(new Intent(this, ClickerService.class), conn, BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unbindService(conn); } catch (Exception ignored) {}
        ClickerService.setCallback(null);
    }

    // ---------------- UI 构建 ----------------

    private TextView txt(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.getPaint().setFakeBoldText(true);
        t.setPadding(0, dp(5), 0, dp(5));
        return t;
    }

    private TextView section(String s) {
        TextView t = txt(s, 15, true);
        t.setTextColor(Color.rgb(43, 87, 154));
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private EditText numField(LinearLayout parent, String label, int value) {
        TextView l = txt(label, 13, false);
        parent.addView(l);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setMinWidth(dp(120));
        parent.addView(e);
        return e;
    }

    /** 构建 5 组，每组：启用勾选 + 若干动作行 + 添加动作按钮 */
    private void buildGroups() {
        groupsBox.removeAllViews();
        final CheckBox[] on = new CheckBox[ClickConfig.GROUPS];
        for (int i = 0; i < ClickConfig.GROUPS; i++) {
            final int gi = i;
            ClickConfig.Group g = cfg.groups[i];

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.rgb(240, 243, 248));
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(6), 0, dp(6));
            card.setLayoutParams(lp);

            on[i] = new CheckBox(this);
            on[i].setText("第 " + (i + 1) + " 组（启用）");
            on[i].setChecked(g.enabled);
            final CheckBox onRef = on[i];
            on[i].setOnClickListener(v -> { g.enabled = onRef.isChecked(); });
            card.addView(on[i]);

            // 已有动作行
            for (int k = 0; k < g.actions.size(); k++) {
                addActionRow(card, gi, k, g.actions.get(k));
            }

            // 添加动作按钮
            Button add = btn("+ 添加动作点");
            final LinearLayout cardRef = card;
            add.setOnClickListener(v -> {
                ClickConfig.Action a = new ClickConfig.Action();
                cfg.groups[gi].actions.add(a);
                addActionRow(cardRef, gi, cfg.groups[gi].actions.size() - 1, a);
            });
            card.addView(add);

            // 删除组最后一个动作
            Button del = btn("- 删除最后一个动作");
            del.setOnClickListener(v -> {
                List<ClickConfig.Action> acts = cfg.groups[gi].actions;
                if (!acts.isEmpty()) {
                    acts.remove(acts.size() - 1);
                    buildGroups();  // 重建以刷新行号
                }
            });
            card.addView(del);

            groupsBox.addView(card);
        }
    }

    /** 一个动作编辑行：类型下拉 + X/Y(+X2/Y2) + 时长 */
    private void addActionRow(LinearLayout card, final int gi, final int k, final ClickConfig.Action a) {
        TextView tag = txt("动作 " + (k + 1), 12, true);
        card.addView(tag);

        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"轻点(Tap)", "长按(Long)", "滑动(Swipe)"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(Math.max(0, Math.min(2, a.type)));
        final Spinner spRef = sp;
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                a.type = pos;
                // 类型变化后重建该组，显示/隐藏对应输入框
                // 简化：始终显示全部字段，用户按需填
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        card.addView(sp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final EditText ex = coord(row, "X", a.x);
        final EditText ey = coord(row, "Y", a.y);
        card.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        final EditText ex2 = coord(row2, "终点X(滑动)", a.x2);
        final EditText ey2 = coord(row2, "终点Y(滑动)", a.y2);
        card.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        final EditText eHold = coord(row3, "长按毫秒", a.holdMs);
        final EditText eSwipe = coord(row3, "滑动毫秒", a.swipeMs);
        card.addView(row3);

        // 输入变化写回对象
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                a.type = spRef.getSelectedItemPosition();
                a.x = parseInt(ex.getText().toString());
                a.y = parseInt(ey.getText().toString());
                a.x2 = parseInt(ex2.getText().toString());
                a.y2 = parseInt(ey2.getText().toString());
                a.holdMs = parseInt(eHold.getText().toString());
                a.swipeMs = parseInt(eSwipe.getText().toString());
            }
        };
        ex.addTextChangedListener(watcher);
        ey.addTextChangedListener(watcher);
        ex2.addTextChangedListener(watcher);
        ey2.addTextChangedListener(watcher);
        eHold.addTextChangedListener(watcher);
        eSwipe.addTextChangedListener(watcher);
    }

    private EditText coord(LinearLayout row, String label, int value) {
        TextView l = new TextView(this);
        l.setText(label + ":");
        l.setTextSize(12);
        l.setPadding(0, 0, dp(4), 0);
        row.addView(l);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(e);
        return e;
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    // ---------------- 逻辑 ----------------

    /** 从界面收集到 cfg */
    private void collect() {
        cfg.orderSimultaneous = chkSimultaneous.isChecked();
        cfg.actionIntervalMs = Math.max(0, parseInt(edActionInterval.getText().toString()));
        cfg.cycleGapMs = Math.max(0, parseInt(edCycleGap.getText().toString()));
        cfg.longHoldMs = Math.max(1, parseInt(edHold.getText().toString()));
        cfg.maxMinutes = Math.max(0, parseInt(edMaxMin.getText().toString()));
        cfg.maxActions = Math.max(0, parseInt(edMaxActions.getText().toString()));
        // 动作对象通过 TextWatcher 已实时写回，这里只需确认启用状态
        for (int i = 0; i < ClickConfig.GROUPS; i++) {
            CheckBox cb = (CheckBox) ((LinearLayout) groupsBox.getChildAt(i)).getChildAt(0);
            cfg.groups[i].enabled = cb != null && cb.isChecked();
        }
    }

    private void toggleRun() {
        collect();
        cfg.save(this);
        if (!isAccessibilityOn()) {
            toast("请先开启无障碍权限");
            openAccessibility();
            return;
        }
        if (service == null) {
            toast("服务未连接，请稍候重试");
            return;
        }
        if (service.isRunning()) {
            stopService(new Intent(this, ClickerService.class));
            btnStart.setText("开始运行");
            toast("已停止");
        } else {
            startService(new Intent(this, ClickerService.class)
                    .setAction(ClickerService.ACTION_START));
            btnStart.setText("停止");
        }
    }

    private void openAccessibility() {
        Intent it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        try { startActivity(it); } catch (Exception e) { toast("无法打开设置，请手动进入"); }
    }

    private boolean isAccessibilityOn() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getId() != null && info.getId().contains(getPackageName())) return true;
        }
        return false;
    }

    private void refreshAccessStatus() {
        boolean on = isAccessibilityOn();
        tvStatus.setText(on ? "无障碍权限：已开启 ✓" : "无障碍权限：未开启（点上方按钮）");
        tvStatus.setTextColor(on ? Color.rgb(22, 160, 90) : Color.rgb(198, 54, 45));
    }

    private void refreshStatus() {
        if (service != null) onStatus(service.isRunning(), 0, 0);
    }

    @Override
    public void onStatus(boolean running, long count, long elapsedSec) {
        runOnUiThread(() -> {
            tvCount.setText("已执行 " + count + " 次 · 运行 " + elapsedSec + " 秒");
            btnStart.setText(running ? "停止" : "开始运行");
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
