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
    private static final String ENGINE = "com.google.android.apps.inputmethod.libs.hmm.HmmEngineInterfaceImpl";

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
                    @Override protected void beforeHookedMethod(MethodHookParam x) {
                        if (!Boolean.TRUE.equals(x.args[1])) return;

                        Object candidate = x.args[0];
                        Object text = field(candidate, "a");
                        Object indexObj = field(candidate, "m");
                        if (!(indexObj instanceof Integer)) return;
                        int index = (Integer) indexObj;

                        try {
                            Object hdl = findFieldInHierarchy(x.thisObject, "B");
                            Object engine = findFieldInHierarchy(hdl, "j");
                            if (engine == null || !ENGINE.equals(engine.getClass().getName())) return;

                            Method tokenMethod = engine.getClass().getMethod("i", int.class, int.class);
                            Method rangeMethod = engine.getClass().getMethod("p", long.class);
                            Method candidateTokenMethod = engine.getClass().getMethod("i", int.class, int.class);
                            Method normalizedMethod = engine.getClass().getMethod("u", long.class);
                            Object candidateToken = candidateTokenMethod.invoke(engine, index, 0);
                            Object range = rangeMethod.invoke(engine, ((Long) candidateToken).longValue());

                            Field startField = range.getClass().getField("startVertexIndex");
                            Field endField = range.getClass().getField("endVertexIndex");
                            int startVertex = startField.getInt(range);
                            int endVertex = endField.getInt(range);

                            XposedBridge.log(TAG + ": RANGE candidate=" + text
                                    + " index=" + index
                                    + " start=" + startVertex
                                    + " end=" + endVertex);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": TOKEN candidate=" + text
                                    + " error=" + t.getClass().getSimpleName());
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

    private static Object findFieldInHierarchy(Object o, String n) throws Exception {
        if (o == null) return null;
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
        return null;
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

    private static String className(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }
}
