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
                        Object learned = findFieldInHierarchy(x.thisObject, "O");
                        Object learnedFlag = findFieldInHierarchy(x.thisObject, "P");

                        XposedBridge.log(TAG
                                + ": STATE candidate=" + text
                                + " index=" + index
                                + " O=" + summarize(learned)
                                + " P=" + learnedFlag);

                        if (learned != null && "hcv".equals(learned.getClass().getSimpleName())) {
                            Object a = field(learned, "a");
                            Object b = field(learned, "b");
                            Object c = field(learned, "c");
                            Object e = field(learned, "e");
                            XposedBridge.log(TAG
                                    + ": HCV a=" + summarizeValue(a)
                                    + " b=" + summarizeValue(b)
                                    + " c=" + summarizeValue(c)
                                    + " e=" + summarizeValue(e));
                        }
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
        return o.getClass().getSimpleName();
    }

    private static String summarizeValue(Object o) {
        if (o == null) return "null";
        if (o.getClass().isArray()) {
            if (o instanceof Object[]) return java.util.Arrays.deepToString((Object[]) o);
            if (o instanceof int[]) return java.util.Arrays.toString((int[]) o);
            if (o instanceof long[]) return java.util.Arrays.toString((long[]) o);
            if (o instanceof boolean[]) return java.util.Arrays.toString((boolean[]) o);
            if (o instanceof byte[]) return "byte[" + ((byte[]) o).length + "]";
            if (o instanceof char[]) return java.util.Arrays.toString((char[]) o);
            if (o instanceof short[]) return java.util.Arrays.toString((short[]) o);
            if (o instanceof float[]) return java.util.Arrays.toString((float[]) o);
            if (o instanceof double[]) return java.util.Arrays.toString((double[]) o);
        }
        return String.valueOf(o);
    }
}
