package com.cloudphone.clicker;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
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
    private TextView tvStatus, tvCount, tvKeyEngine;
    private Button btnStart;
    private LinearLayout groupsBox;
    private CheckBox chkSimultaneous;
    private EditText edActionInterval, edCycleGap, edHold, edMaxMin, edMaxActions;

    private volatile boolean serviceRunning = false;
    private final android.content.BroadcastReceiver statusReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            if (ClickerService.ACTION_STATUS.equals(i.getAction())) {
                onStatus(i.getBooleanExtra("running", false),
                        i.getLongExtra("count", 0), i.getLongExtra("elapsed", 0));
            }
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
        Button btnRefresh = btn("刷新无障碍状态（开完设置后点这里）");
        btnRefresh.setOnClickListener(v -> { refreshAccessStatus(); toast("状态已刷新"); });
        root.addView(btnRefresh);

        // ---- 按键引擎状态（发真实键盘事件需要 Root 或系统签名）
        tvKeyEngine = txt(KeyInjector.statusText(), 12, true);
        tvKeyEngine.setTextColor(KeyInjector.available()
                ? Color.rgb(22, 160, 90) : Color.rgb(150, 100, 20));
        root.addView(tvKeyEngine);
        root.addView(txt("说明：" + KeyInjector.detailText(), 11, false));

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
        ClickerService.setCallback(this);
        android.content.IntentFilter f = new android.content.IntentFilter(ClickerService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, f);
        }
        // 主动请求服务回报一次状态
        startService(new Intent(this, ClickerService.class).setAction(ClickerService.ACTION_STATUS));
    }

    @Override
    protected void onPause() {
        super.onPause();
        ClickerService.setCallback(null);
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
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
                new String[]{"轻点(Tap)", "长按(Long)", "滑动(Swipe)", "键盘按键(Key)"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setSelection(Math.max(0, Math.min(3, a.type)));
        final Spinner spRef = sp;
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                a.type = pos;
                // 简化：始终显示全部字段，用户按需填（按键行在下方）
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        card.addView(sp);

        // 键盘按键行：按键下拉 + Ctrl/Alt/Shift
        TextView keyTag = txt("键盘按键（类型选 Key 时生效）", 12, false);
        card.addView(keyTag);
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        Spinner keySp = new Spinner(this);
        ArrayAdapter<String> kad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, KeyInjector.KEY_NAMES);
        kad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        keySp.setAdapter(kad);
        int kidx = -1;
        for (int j = 0; j < KeyInjector.KEY_NAMES.length; j++) {
            if (KeyInjector.KEY_NAMES[j].equalsIgnoreCase(a.keyName)) { kidx = j; break; }
        }
        keySp.setSelection(Math.max(0, kidx));
        final Spinner keySpRef = keySp;
        keySp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                a.keyName = KeyInjector.KEY_NAMES[pos];
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        keyRow.addView(keySp);
        final CheckBox kc = new CheckBox(this); kc.setText("Ctrl"); kc.setChecked(a.keyCtrl);
        final CheckBox ka = new CheckBox(this); ka.setText("Alt");  ka.setChecked(a.keyAlt);
        final CheckBox ks = new CheckBox(this); ks.setText("Shift"); ks.setChecked(a.keyShift);
        keyRow.addView(kc); keyRow.addView(ka); keyRow.addView(ks);
        card.addView(keyRow);
        a.keyCtrl = kc.isChecked(); a.keyAlt = ka.isChecked(); a.keyShift = ks.isChecked();
        a.keyName = KeyInjector.KEY_NAMES[Math.max(0, kidx)];

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
                a.keyCtrl = kc.isChecked(); a.keyAlt = ka.isChecked(); a.keyShift = ks.isChecked();
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
        // 若用了键盘按键但引擎不可用，提前提示
        boolean hasKey = false;
        for (ClickConfig.Group g : cfg.groups) {
            for (ClickConfig.Action a : g.actions) {
                if (a.type == ClickConfig.ACT_KEY) { hasKey = true; break; }
            }
        }
        if (hasKey && !KeyInjector.available()) {
            toast("按键引擎不可用：需要云手机 Root 或系统签名");
            return;
        }
        if (serviceRunning) {
            stopService(new Intent(this, ClickerService.class).setAction(ClickerService.ACTION_STOP));
            serviceRunning = false;
            btnStart.setText("开始运行");
        } else {
            startService(new Intent(this, ClickerService.class)
                    .setAction(ClickerService.ACTION_START));
            serviceRunning = true;
            btnStart.setText("停止");
        }
    }

    private void openAccessibility() {
        Intent it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        try { startActivity(it); } catch (Exception e) { toast("无法打开设置，请手动进入"); }
    }

    private boolean isAccessibilityOn() {
        // 权威判断：读系统设置里已启用的服务列表（与设置页开关一致）。
        // 不用 getEnabledAccessibilityServiceList：它只列出已成功绑定的服务，
        // 进程未起来/绑定延迟时会误报「未开启」。
        try {
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat != null) {
                String pkg = getPackageName();
                String shortName = pkg + "/." + ClickerService.class.getSimpleName();
                String fullName  = pkg + "/" + ClickerService.class.getName();
                // 精确匹配
                for (String part : flat.split(":")) {
                    String p = part.trim();
                    if (p.equalsIgnoreCase(shortName) || p.equalsIgnoreCase(fullName)) return true;
                }
                // 宽松兜底：云手机常把组件名写成各种形式，只要包含包名+服务名就算开
                if (flat.contains(pkg) && flat.toLowerCase().contains("clickerservice")) return true;
            }
        } catch (Exception ignored) { }
        // 兜底：设置读取失败时用兼容判断（不限制反馈类型）
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    if (info.getId() != null && info.getId().contains(getPackageName())) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void refreshAccessStatus() {
        boolean on = isAccessibilityOn();
        tvStatus.setText(on ? "无障碍权限：已开启 ✓" : "无障碍权限：未开启（点上方按钮）");
        tvStatus.setTextColor(on ? Color.rgb(22, 160, 90) : Color.rgb(198, 54, 45));
    }

    @Override
    public void onStatus(boolean running, long count, long elapsedSec) {
        runOnUiThread(() -> {
            serviceRunning = running;
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
