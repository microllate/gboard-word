package com.microllate.gboardword;

import android.app.Application;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Iterator;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "GboardWord";
    private static final String GBOARD = "com.google.android.inputmethod.latin";
    private static final String PROCESSOR = "com.google.android.apps.inputmethod.libs.chinese.ime.hmm.AbstractHmmChineseDecodeProcessor";
    private static final String HMM_PROCESSOR = "com.google.android.apps.inputmethod.libs.hmm.AbstractHmmDecodeProcessor";
    private static PersonalDb db;
    private static boolean iteratorHookInstalled;

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
                        if (!iteratorHookInstalled) {
                            Object hdl = findFieldInHierarchy(x.thisObject, "m");
                            if (hdl != null) {
                                installIteratorHook(hdl.getClass());
                            }
                        }


                        if (learned != null && "hcv".equals(learned.getClass().getSimpleName())) {
                            Object a = field(learned, "a");
                            Object b = field(learned, "b");
                            Object e = field(learned, "e");
                            String phrase = a == null ? null : String.valueOf(a);
                            String pinyin = join(b);
                            if (Boolean.TRUE.equals(e) && phrase != null
                                    && !phrase.isEmpty() && b != null) {
                                try {
                                    ensureDb();
                                    if (db != null) {
                                        int count = db.record(pinyin, phrase);
                                        XposedBridge.log(TAG + ": SAVED phrase=" + phrase
                                                + " pinyin=" + pinyin + " count=" + count);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": DB save failed: "
                                            + android.util.Log.getStackTraceString(t));
                                }
                            }
                        } else {
                            XposedBridge.log(TAG + ": SELECTED text=" + text
                                    + " index=" + index + " O=" + summarize(learned));
                        }
                    }
                });
                hooked++;
            }

            XposedBridge.log(TAG + ": Z hook ready; f() will be hooked from runtime hdl class");

            XposedBridge.log(TAG + ": hooked Z candidate selection methods=" + hooked);

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook install failed: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private static synchronized void installIteratorHook(Class<?> runtimeClass) {
        if (iteratorHookInstalled || runtimeClass == null) return;
        try {
            Class<?> c = runtimeClass;
            while (c != null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"f".equals(m.getName()) || m.getParameterTypes().length != 0) continue;
                    if (!Iterator.class.isAssignableFrom(m.getReturnType())) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam x) {
                        Object result = x.getResult();
                        XposedBridge.log(TAG + ": F_RETURN class="
                                + x.thisObject.getClass().getName()
                                + " iterator=" + summarize(result));
                    }                    });
                        }
                    });
                    iteratorHookInstalled = true;
                    XposedBridge.log(TAG + ": hooked runtime f() class=" + c.getName());
                    return;
                }
                c = c.getSuperclass();
            }
            XposedBridge.log(TAG + ": runtime f() not found class=" + runtimeClass.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": runtime f() hook failed: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private static synchronized void ensureDb() {
        if (db != null) return;
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"),
                    "currentApplication");
            if (app == null) {
                XposedBridge.log(TAG + ": DB init FAILED: currentApplication=null");
                return;
            }
            db = new PersonalDb(app);
            XposedBridge.log(TAG + ": DB init OK (lazy)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": DB init FAILED: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private static String join(Object o) {
        if (o == null) return "null";
        if (o instanceof Object[]) {
            Object[] a = (Object[]) o;
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) s.append(' ');
                s.append(a[i]);
            }
            return s.toString();
        }
        return String.valueOf(o);
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
        return findFieldInHierarchy(o, n);
    }

    private static void fieldSet(Object o, String n, Object value) {
        if (o == null) return;
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    f.set(o, value);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": fieldSet failed: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private static String summarize(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }
}
