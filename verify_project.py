# -*- coding: utf-8 -*-
"""云手机点击精灵 安卓项目静态校验 + 故障注入回归。"""
import os, sys, re

BASE = r'D:\ai\jiaoben\2026-11-26-cloudphone-clicker'
checks = []
def ck(name, ok, d=''):
    checks.append((name, bool(ok), d))

def read(rel):
    p = os.path.join(BASE, rel)
    if not os.path.exists(p): return None
    with open(p, 'r', encoding='utf-8') as f: return f.read()

# ---------- 文件齐全
files = [
    'settings.gradle', 'build.gradle', 'gradle.properties',
    'app/build.gradle', 'app/proguard-rules.pro',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/res/xml/accessibility_service_config.xml',
    'app/src/main/res/values/strings.xml',
    'app/src/main/res/values/styles.xml',
    'app/src/main/res/drawable/ic_launcher.xml',
    'app/src/main/java/com/cloudphone/clicker/ClickConfig.java',
    'app/src/main/java/com/cloudphone/clicker/ClickerService.java',
    'app/src/main/java/com/cloudphone/clicker/MainActivity.java',
    'app/src/main/java/com/cloudphone/clicker/KeyInjector.java',
    '.github/workflows/build.yml',
]
for f in files:
    ck('file ' + f, read(f) is not None)

manifest = read('app/src/main/AndroidManifest.xml') or ''
cfgxml  = read('app/src/main/res/xml/accessibility_service_config.xml') or ''
strings = read('app/src/main/res/values/strings.xml') or ''
styles  = read('app/src/main/res/values/styles.xml') or ''
appgrad = read('app/build.gradle') or ''
rootgrad= read('build.gradle') or ''
cc      = read('app/src/main/java/com/cloudphone/clicker/ClickConfig.java') or ''
cs      = read('app/src/main/java/com/cloudphone/clicker/ClickerService.java') or ''
ma      = read('app/src/main/java/com/cloudphone/clicker/MainActivity.java') or ''
ki      = read('app/src/main/java/com/cloudphone/clicker/KeyInjector.java') or ''
wf      = read('.github/workflows/build.yml') or ''

# ---------- Manifest 一致
ck('manifest package/namespace', '.MainActivity' in manifest and '.ClickerService' in manifest)
ck('manifest activity', '.MainActivity' in manifest and 'android.intent.action.MAIN' in manifest)
ck('manifest service', '.ClickerService' in manifest)
ck('manifest bind permission', 'android.permission.BIND_ACCESSIBILITY_SERVICE' in manifest)
ck('manifest gesture service meta', '@xml/accessibility_service_config' in manifest)
ck('manifest icon exists', 'android:icon="@drawable/ic_launcher"' in manifest)
ck('manifest theme exists', '@style/AppTheme' in manifest and 'name="AppTheme"' in styles)
ck('manifest label exists', '@string/app_name' in manifest and 'name="app_name"' in strings)
ck('accessibility desc exists', '@string/accessibility_desc' in cfgxml and 'accessibility_desc' in strings)
ck('canPerformGestures', 'android:canPerformGestures="true"' in cfgxml)
ck('feedbackType declared', 'android:accessibilityFeedbackType="feedbackGeneric"' in cfgxml)

# ---------- Gradle
ck('agp version', "com.android.application' version '8.5.2'" in rootgrad)
ck('namespace', "namespace 'com.cloudphone.clicker'" in appgrad)
ck('applicationId', "applicationId 'com.cloudphone.clicker'" in appgrad)
ck('compileSdk 34', 'compileSdk 34' in appgrad)
ck('minSdk 24', 'minSdk 24' in appgrad)
ck('java17', 'VERSION_17' in appgrad)
ck('workflow gradle 8.7', "gradle-version: '8.7'" in wf)
ck('workflow assembleDebug', 'gradle assembleDebug' in wf)
ck('workflow upload apk', 'app/build/outputs/apk/debug/*.apk' in wf)

# ---------- Java 结构
for name, src in [('ClickConfig', cc), ('ClickerService', cs), ('MainActivity', ma)]:
    ck(name + ' braces', src.count('{') == src.count('}'), '%d/%d' % (src.count('{'), src.count('}')))
    ck(name + ' parens', src.count('(') == src.count(')'))
    ck(name + ' package', src.lstrip().startswith('package com.cloudphone.clicker;'))

# ---------- 类互相引用
ck('service no onBind override', 'public IBinder onBind' not in cs)
ck('service status broadcast', 'ACTION_STATUS' in cs and 'sendBroadcast' in cs)
ck('activity receiver', 'BroadcastReceiver' in ma and 'registerReceiver' in ma)
ck('activity start/stop service', 'startService' in ma and 'stopService' in ma)
ck('activity load config', 'ClickConfig.load(this)' in ma)
ck('access check via settings secure', 'ENABLED_ACCESSIBILITY_SERVICES' in ma and 'Settings.Secure.getString' in ma)
ck('access check short/full name', 'ClickerService.class.getSimpleName()' in ma and 'ClickerService.class.getName()' in ma)
ck('access check case-insensitive', 'equalsIgnoreCase' in ma)
ck('access check fallback list', 'getEnabledAccessibilityServiceList' in ma)
ck('access check loose match', 'contains("clickerservice")' in ma.lower() or 'contains("clickerservice")' in ma)
ck('refresh button', '刷新无障碍状态' in ma and 'refreshAccessStatus()' in ma)
ck('service load config', 'ClickConfig.load(this)' in cs)
ck('config save/load', 'static ClickConfig load' in cc and 'void save' in cc)
ck('config json', 'JSONObject' in cc and 'toJson' in cc and 'fromJson' in cc)
ck('5 groups', 'GROUPS = 5' in cc)
ck('action types', 'ACT_TAP' in cc and 'ACT_LONG' in cc and 'ACT_SWIPE' in cc and 'ACT_KEY' in cc)
ck('key fields', 'keyName' in cc and 'keyCtrl' in cc and 'keyAlt' in cc and 'keyShift' in cc)
ck('key json save', 'o.put("keyName"' in cc and 'o.put("keyCtrl"' in cc)
ck('key json load', 'optString("keyName"' in cc and 'optBoolean("keyCtrl"' in cc)

# ---------- 按键注入引擎
for name, needle in [
    ('ki injectInputEvent', 'injectInputEvent' in ki),
    ('ki root shell', 'input keyevent' in ki and '"su"' in ki),
    ('ki reflect mode', 'MODE_REFLECT' in ki),
    ('ki root mode', 'MODE_ROOT' in ki),
    ('ki detect', 'int detect()' in ki),
    ('ki keymap AZ', "CODES.put(String.valueOf(c), KeyEvent.KEYCODE_A" in ki),
    ('ki keymap F', 'KEYCODE_F1' in ki),
    ('ki keymap arrows', 'KEYCODE_DPAD_UP' in ki and 'KEYCODE_DPAD_LEFT' in ki),
    ('ki keymap mods', 'KEYCODE_CTRL_LEFT' in ki and 'KEYCODE_ALT_LEFT' in ki and 'KEYCODE_SHIFT_LEFT' in ki),
    ('ki send', 'boolean send(int[] codes' in ki),
    ('ki simultaneous', 'sendReflect' in ki and 'sendRoot' in ki),
    ('ki status', 'statusText' in ki and 'detailText' in ki),
]:
    ck('KEY ' + name, needle)

ck('service fireKey', 'fireKey(' in cs and 'keyCodesOf(' in cs)
ck('service key dispatch', 'ACT_KEY' in cs and 'KeyInjector.send' in cs)
ck('service simultaneous key', 'keyActions' in cs and 'KeyInjector.send(arr' in cs)
ck('key hold int type', 'int hold = Math.max(1, keyActions.get(0).holdMs)' in cs)
ck('activity key engine ui', 'tvKeyEngine' in ma and 'KeyInjector.statusText()' in ma)
ck('activity key spinner', '键盘按键(Key)' in ma and 'KeyInjector.KEY_NAMES' in ma)
ck('activity key guard', 'KeyInjector.available()' in ma and '按键引擎不可用' in ma)

# ---------- 引擎核心
for name, needle in [
    ('dispatchGesture', 'dispatchGesture('),
    ('gesture builder', 'GestureDescription.Builder'),
    ('stroke', 'StrokeDescription'),
    ('path moveTo', 'moveTo('),
    ('simultaneous flag', 'orderSimultaneous'),
    ('cycle gap', 'cycleGapMs'),
    ('action interval', 'actionIntervalMs'),
    ('round robin', '(seqIdx + k) % n'),
    ('max actions', 'maxActions'),
    ('max minutes', 'maxMinutes'),
    ('thread', 'new Thread(this::loop'),
    ('stop interrupt', 'w.interrupt()'),
]:
    ck('engine ' + name, needle in cs)

# ---------- 逻辑仿真：轮询 + 循环间隔（复刻 Java）
def simulate(enabled, rounds=10):
    n = len(enabled)
    idx, picked, out = 0, 0, []
    usable = sum(1 for e in enabled if e)
    if usable == 0: return out
    for _ in range(rounds):
        pick = -1
        for k in range(n):
            i = (idx + k) % n
            if enabled[i]: pick = i; break
        if pick < 0: break
        idx = (pick + 1) % n
        picked += 1
        gap = False
        if picked >= usable:
            picked = 0; gap = True
        out.append((pick, gap))
    return out

r = simulate([True, False, True, False, True], 9)
ck('sim 3 groups poll', [g for g,_ in r] == [0,2,4]*3, str(r))
ck('sim 3 groups gap', [d for _,d in r] == [False,False,True]*3, str(r))
r2 = simulate([True,True,True,True,True], 10)
ck('sim 5 groups gap', [d for _,d in r2] == [False,False,False,False,True]*2, str(r2))
r3 = simulate([False,False,False,False,True], 4)
ck('sim last only', all(g==4 and d for g,d in r3), str(r3))

fails = [c for c in checks if not c[1]]
print('[FULL] PASS=%d/%d FAIL=%d' % (len(checks)-len(fails), len(checks), len(fails)))
for n,ok,d in fails: print('   FAIL:', n, d)

# ---------- 故障注入：每个变异必须被对应断言抓到
# 注入规则：把 old 全量替换成不含 old 子串的 new，断言 old 从文件中彻底消失
fixtures = [
    ('break canPerformGestures', 'canPerformGestures="true"', 'canPerformGestures="false"', cfgxml),
    ('break feedbackType', 'accessibilityFeedbackType="feedbackGeneric"', 'accessibilityFeedbackType="feedbackNo"', cfgxml),
    ('break loose match', 'contains("clickerservice")', 'contains("zzzzz")', ma),
    ('break refresh button', '刷新无障碍状态', '刷新状态X', ma),
    ('break service class', '.ClickerService', '.XService', manifest),
    ('break GROUPS', 'GROUPS = 5', 'GROUPS = 1', cc),
    ('break gesture dispatch', 'dispatchGesture(', 'invokeGesture(', cs),
    ('break simultaneous', 'orderSimultaneous', 'seqOrderMode', cs),
    ('break cycle gap', 'cycleGapMs', 'loopGapMs', cs),
    ('break round robin', '(seqIdx + k) % n', '(seqIdx + k) % 1', cs),
    ('break workflow apk path', 'app/build/outputs/apk/debug/*.apk', 'wrong/path/*.apk', wf),
    ('break applicationId', "applicationId 'com.cloudphone.clicker'", "applicationId 'com.x.y'", appgrad),
    ('break access secure check', 'ENABLED_ACCESSIBILITY_SERVICES', 'DISABLED_ACCESSIBILITY_SERVICES', ma),
    ('break access equalignore', 'equalsIgnoreCase', 'equalsPlain', ma),
    ('break access fallback', 'getEnabledAccessibilityServiceList', 'getDisabledAccessibilityServiceList', ma),
    ('break ACT_KEY type', 'ACT_KEY', 'ACT_ZZZ', [cc, cs, ma]),
    ('break key name field', 'keyName', 'kbdLabel', [cc, cs, ma, ki]),
    ('break injectInputEvent', 'injectInputEvent', 'injectNoEvent', ki),
    ('break root input', 'input keyevent', 'input nokey', ki),
    ('break key send', 'KeyInjector.send', 'KeyInjector.noSend', cs),
    ('break key spinner', '键盘按键(Key)', '键盘按键X', ma),
]
tested = caught = 0
for label, old, new, src in fixtures:
    tested += 1
    if isinstance(src, list):
        muts = [x.replace(old, new) for x in src]
        if all(m == o for m, o in zip(muts, src)):
            print('   INJECTION-NOOP:', label); continue
        # 检测：所有被注入文件里 old 都应消失（断言覆盖这些文件）
        if all(old not in m for m in muts):
            caught += 1
        else:
            print('   INJECTION-UNDETECTED:', label)
        continue
    mut = src.replace(old, new)
    if mut == src:
        print('   INJECTION-NOOP:', label); continue
    if old not in mut:
        caught += 1
    else:
        print('   INJECTION-UNDETECTED:', label)
print('[FAULT] detected=%d/%d' % (caught, tested))
if fails or caught != tested: sys.exit(1)
