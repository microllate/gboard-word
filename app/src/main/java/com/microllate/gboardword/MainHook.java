package com.microllate.gboardword;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
            for (Method m : pc.getDeclaredMethods()) {
                if (!"Z".equals(m.getName())) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 2 || ps[1] != boolean.class) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam x) {
                        if (!Boolean.TRUE.equals(x.args[1])) return;

                        Object candidate = x.args[0];
                        Object text = field(candidate, "a");
                        Object index = field(candidate, "m");

                        Object composing = findFieldInHierarchy(x.thisObject, "G");
                        Object learned = findFieldInHierarchy(x.thisObject, "O");
                        Object selected = findFieldInHierarchy(x.thisObject, "U");
                        Object learnedFlag = findFieldInHierarchy(x.thisObject, "P");
                        Object mHdl = findFieldInHierarchy(x.thisObject, "m");

                        XposedBridge.log(TAG
                                + ": STATE candidate=" + text
                                + " index=" + index
                                + " G=" + composing
                                + " O=" + summarize(learned)
                                + " U=" + summarize(selected)
                                + " P=" + learnedFlag
                                + " m=" + className(mHdl));
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + ": hooked Z candidate selection methods=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook install failed: " + android.util.Log.getStackTraceString(t));
        }
    }

    private static Object findFieldInHierarchy(Object o, String n) {
        if (o == null) return null;
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
        return null;
    }

    private static Object field(Object o, String n) {
        if (o == null) return null;
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    return f.get(o);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
        return null;
    }

    private static String summarize(Object o) {
        if (o == null) return "null";
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean) {
            return String.valueOf(o);
        }
        return o.getClass().getSimpleName();
    }

    private static String className(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
