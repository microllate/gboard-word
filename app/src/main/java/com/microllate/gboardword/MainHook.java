package com.microllate.gboardword;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "GboardWord";
    private static final String GBOARD = "com.google.android.inputmethod.latin";
    private static final String PROCESSOR = "com.google.android.apps.inputmethod.libs.chinese.ime.hmm.AbstractHmmChineseDecodeProcessor";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!GBOARD.equals(p.packageName)) return;
        try {
            Class<?> pc = XposedHelpers.findClass(PROCESSOR, p.classLoader);
            int hooked = 0;
            for (java.lang.reflect.Method m : pc.getDeclaredMethods()) {
                if (!"Z".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2 || ps[1] != boolean.class) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam x) {
                        if (!Boolean.TRUE.equals(x.args[1])) return;
                        Object c = x.args[0];
                        Object processor = x.thisObject;
                        XposedBridge.log(TAG + ": SELECTED candidate=" + field(c, "a"));
                        Object hdl = field(processor, "B");
                        dump(hdl, "hdl");
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + ": hooked Z candidate selection methods=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook install failed: " + android.util.Log.getStackTraceString(t));
        }
    }

    private static Object field(Object o, String n) {
        if (o == null) return null;
        try {
            Field f = o.getClass().getDeclaredField(n);
            f.setAccessible(true);
            return f.get(o);
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }

    private static void dump(Object o, String label) {
        if (o == null) {
            XposedBridge.log(TAG + ": " + label + "=null");
            return;
        }
        Class<?> c = o.getClass();
        StringBuilder s = new StringBuilder(TAG + ": " + label + " class=" + c.getName());
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                s.append(" | ").append(f.getName()).append(":")
                 .append(f.getType().getName()).append("=")
                 .append(String.valueOf(f.get(o)));
            } catch (Throwable e) {
                s.append(" | ").append(f.getName()).append(":<error>");
            }
        }
        XposedBridge.log(s.toString());
    }
}