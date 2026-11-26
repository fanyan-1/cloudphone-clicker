package com.cloudphone.clicker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置模型 + SharedPreferences 持久化。
 * 保留电脑版概念：5 组序列；每组若干"动作点"(X,Y,类型)；组合方式(同时/依次)；
 * 按键间隔、循环间隔、按下时长、时长上限、次数上限。
 */
public class ClickConfig {
    // 动作类型
    public static final int ACT_TAP = 0;      // 轻点
    public static final int ACT_LONG = 1;     // 长按
    public static final int ACT_SWIPE = 2;    // 滑动(从 x,y 到 x2,y2)
    public static final int ACT_KEY = 3;      // 键盘按键(真实 KeyEvent)

    /** 一个动作点 */
    public static class Action {
        public int type = ACT_TAP;
        public int x = 0, y = 0;
        public int x2 = 0, y2 = 0;   // 滑动终点
        public int holdMs = 80;      // 长按时长
        public int swipeMs = 200;    // 滑动时长
        // 键盘按键（ACT_KEY）
        public String keyName = "A";          // 主键名，如 W / F1 / 空格
        public boolean keyCtrl = false, keyAlt = false, keyShift = false;  // 修饰键

        public Action() {}

        public Action(int type, int x, int y) {
            this.type = type; this.x = x; this.y = y;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("type", type); o.put("x", x); o.put("y", y);
                o.put("x2", x2); o.put("y2", y2);
                o.put("holdMs", holdMs); o.put("swipeMs", swipeMs);
                o.put("keyName", keyName);
                o.put("keyCtrl", keyCtrl); o.put("keyAlt", keyAlt); o.put("keyShift", keyShift);
            } catch (JSONException ignored) {}
            return o;
        }

        static Action fromJson(JSONObject o) {
            Action a = new Action();
            try {
                a.type = o.optInt("type", ACT_TAP);
                a.x = o.optInt("x", 0); a.y = o.optInt("y", 0);
                a.x2 = o.optInt("x2", 0); a.y2 = o.optInt("y2", 0);
                a.holdMs = o.optInt("holdMs", 80);
                a.swipeMs = o.optInt("swipeMs", 200);
                a.keyName = o.optString("keyName", "A");
                a.keyCtrl = o.optBoolean("keyCtrl", false);
                a.keyAlt = o.optBoolean("keyAlt", false);
                a.keyShift = o.optBoolean("keyShift", false);
            } catch (Exception ignored) {}
            return a;
        }
    }

    /** 一组动作（对应用户界面里的一行） */
    public static class Group {
        public boolean enabled = false;
        public final List<Action> actions = new ArrayList<>();
    }

    public static final int GROUPS = 5;
    public final Group[] groups = new Group[GROUPS];

    // 组合方式
    public boolean orderSimultaneous = false; // false=依次连按  true=同时按下(多点)
    // 调度参数
    public int actionIntervalMs = 500;   // 每次发动作的间隔(组间/组内不同动作间)
    public int cycleGapMs = 0;           // 一轮全部跑完后的额外等待
    public int longHoldMs = 80;          // 默认长按时长
    public int maxMinutes = 0;           // 0=不限
    public int maxActions = 0;           // 0=不限

    public ClickConfig() {
        for (int i = 0; i < GROUPS; i++) groups[i] = new Group();
    }

    /** 该组是否可发（启用且有有效动作） */
    public boolean groupUsable(int i) {
        return groups[i].enabled && !groups[i].actions.isEmpty();
    }

    public int usableGroupCount() {
        int n = 0;
        for (int i = 0; i < GROUPS; i++) if (groupUsable(i)) n++;
        return n;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < GROUPS; i++) {
                Group g = groups[i];
                JSONObject go = new JSONObject();
                go.put("enabled", g.enabled);
                JSONArray acts = new JSONArray();
                for (Action a : g.actions) acts.put(a.toJson());
                go.put("actions", acts);
                arr.put(go);
            }
            o.put("groups", arr);
            o.put("orderSimultaneous", orderSimultaneous);
            o.put("actionIntervalMs", actionIntervalMs);
            o.put("cycleGapMs", cycleGapMs);
            o.put("longHoldMs", longHoldMs);
            o.put("maxMinutes", maxMinutes);
            o.put("maxActions", maxActions);
        } catch (JSONException ignored) {}
        return o;
    }

    static ClickConfig fromJson(String json) {
        ClickConfig c = new ClickConfig();
        try {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.optJSONArray("groups");
            if (arr != null) {
                for (int i = 0; i < Math.min(GROUPS, arr.length()); i++) {
                    JSONObject go = arr.getJSONObject(i);
                    c.groups[i].enabled = go.optBoolean("enabled", false);
                    JSONArray acts = go.optJSONArray("actions");
                    if (acts != null) {
                        for (int k = 0; k < acts.length(); k++) {
                            c.groups[i].actions.add(Action.fromJson(acts.getJSONObject(k)));
                        }
                    }
                }
            }
            c.orderSimultaneous = o.optBoolean("orderSimultaneous", false);
            c.actionIntervalMs = o.optInt("actionIntervalMs", 500);
            c.cycleGapMs = o.optInt("cycleGapMs", 0);
            c.longHoldMs = o.optInt("longHoldMs", 80);
            c.maxMinutes = o.optInt("maxMinutes", 0);
            c.maxActions = o.optInt("maxActions", 0);
        } catch (Exception ignored) {}
        return c;
    }

    private static final String PREF = "clicker_cfg";
    private static final String KEY = "config_v1";

    public static ClickConfig load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, null);
        return raw == null ? new ClickConfig() : fromJson(raw);
    }

    public void save(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().putString(KEY, toJson().toString()).apply();
    }
}
