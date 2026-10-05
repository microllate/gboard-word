package com.microllate.gboardword;

import android.app.Application;
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
    private static final String PROCESSOR =
            "com.google.android.apps.inputmethod.libs.chinese.ime.hmm.AbstractHmmChineseDecodeProcessor";
    private static boolean candidateHookInstalled;
    private static PersonalDb db;
    private static Object candidateEngine;

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
                    @Override
                    protected void afterHookedMethod(MethodHookParam x) {
                        if (!Boolean.TRUE.equals(x.args[1])) return;

                        Object candidate = x.args[0];
                        Object text = field(candidate, "a");
                        Object index = field(candidate, "m");

                        Object hdl = findFieldInHierarchy(x.thisObject, "m");
                        if (!candidateHookInstalled && hdl != null) {
                            installCandidateHookFromF(hdl.getClass());
                        }

                        Object learned = findFieldInHierarchy(x.thisObject, "O");
                        if (learned != null
                                && "hcv".equals(learned.getClass().getSimpleName())) {
                            Object a = field(learned, "a");
                            Object b = field(learned, "b");
                            Object c = field(learned, "c");
                            Object e = field(learned, "e");
                            String phrase = a == null ? null : String.valueOf(a);
                            String pinyin = join(b);

                            if (Boolean.TRUE.equals(e)
                                    && phrase != null
                                    && !phrase.isEmpty()
                                    && b != null) {
                                try {
                                    // Use Gboard's own MutableDictionaryAccessorImpl instead
                                    // of maintaining a separate local dictionary.
                                    saveToGboardDictionary(x.thisObject, hdl, b, c, phrase);

                                    // Keep the old local record for diagnostics for now.
                                    ensureDb();
                                    if (db != null) {
                                        int count = db.record(pinyin, phrase);
                                        XposedBridge.log(TAG + ": LOCAL-SAVED phrase=" + phrase
                                                + " pinyin=" + pinyin + " count=" + count);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + ": dictionary save failed: "
                                            + android.util.Log.getStackTraceString(t));
                                }
                            }
                        } else {
                            XposedBridge.log(TAG + ": SELECTED text=" + text
                                    + " index=" + index);
                        }
                    }
                });
                hooked++;
            }

            // B() is the actual candidate-iterator entry point. It runs before Z(),
            // so hook its returned hdb instead of waiting for candidate selection.
            try {
                Method bMethod = null;
                Class<?> c = pc;
                while (c != null && bMethod == null) {
                    for (Method m : c.getDeclaredMethods()) {
                        if ("B".equals(m.getName())
                                && m.getParameterTypes().length == 0
                                && java.util.Iterator.class.isAssignableFrom(m.getReturnType())) {
                            bMethod = m;
                            break;
                        }
                    }
                    c = c.getSuperclass();
                }

                if (bMethod != null) {
                    bMethod.setAccessible(true);
                    XposedBridge.hookMethod(bMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam x) {
                            try {
                                Object processor = x.thisObject;
                                Object hcv = findFieldInHierarchy(processor, "O");
                                Object pinyin = hcv == null ? null : field(hcv, "b");
                                XposedBridge.log(TAG + ": B() PINYIN=" + join(pinyin));

                                Object iterator = x.getResult();
                                if (iterator != null) {
                                    installCandidateHookExact(iterator.getClass(), processor);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": B() candidate discovery failed: "
                                        + android.util.Log.getStackTraceString(t));
                            }
                        }
                    });
                    XposedBridge.log(TAG + ": hooked B() candidate iterator entry");
                } else {
                    XposedBridge.log(TAG + ": B() not found; candidate hook disabled");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": B() hook failed: "
                        + android.util.Log.getStackTraceString(t));
            }

            XposedBridge.log(TAG + ": Z hook ready; candidate a() will be read-only hooked");
            XposedBridge.log(TAG + ": hooked Z candidate selection methods=" + hooked);

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook install failed: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private static synchronized void saveToGboardDictionary(
            Object processor, Object hdl, Object tokens, Object types, String phrase) {
        if (!(tokens instanceof String[]) || !(types instanceof int[])) {
            XposedBridge.log(TAG + ": dictionary args unexpected tokens="
                    + typeName(tokens) + " types=" + typeName(types));
            return;
        }

        Object accessor = findObjectByTypeName(processor, "MutableDictionaryAccessorImpl", 6);
        if (accessor == null) {
            accessor = findObjectByTypeName(hdl, "MutableDictionaryAccessorImpl", 6);
        }
        if (accessor == null) {
            XposedBridge.log(TAG + ": MutableDictionaryAccessorImpl not found");
            return;
        }

        try {
            Method add = null;
            Class<?> c = accessor.getClass();
            while (c != null && add == null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"b".equals(m.getName())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 4
                            && p[0] == String[].class
                            && p[1] == int[].class
                            && p[2] == String.class
                            && p[3] == boolean.class) {
                        add = m;
                        break;
                    }
                }
                c = c.getSuperclass();
            }

            if (add == null) {
                XposedBridge.log(TAG + ": MutableDictionaryAccessorImpl.b() not found");
                return;
            }

            add.setAccessible(true);
            add.invoke(accessor, tokens, types, phrase, true);
            XposedBridge.log(TAG + ": GBOARD-SAVED phrase=" + phrase
                    + " pinyin=" + join(tokens));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": native dictionary save failed: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private static String typeName(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static synchronized void installCandidateHookFromF(Class<?> runtimeClass) {
        if (candidateHookInstalled || runtimeClass == null) return;
        try {
            Method fMethod = null;
            Class<?> c = runtimeClass;
            while (c != null && fMethod == null) {
                for (Method m : c.getDeclaredMethods()) {
                    if ("f".equals(m.getName())
                            && m.getParameterTypes().length == 0
                            && java.util.Iterator.class.isAssignableFrom(m.getReturnType())) {
                        fMethod = m;
                        break;
                    }
                }
                c = c.getSuperclass();
            }

            if (fMethod == null) {
                XposedBridge.log(TAG + ": hdl.f() not found; no candidate hook installed");
                return;
            }

            fMethod.setAccessible(true);
            XposedBridge.hookMethod(fMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam x) {
                    if (candidateHookInstalled) return;
                    try {
                        Object iterator = x.getResult();
                        if (iterator == null) {
                            XposedBridge.log(TAG + ": f() RETURN=null");
                            return;
                        }

                        Class<?> rc = iterator.getClass();
                        XposedBridge.log(TAG + ": f() RETURN class=" + rc.getName()
                                + " superclass=" + (rc.getSuperclass() == null
                                ? "null" : rc.getSuperclass().getName()));

                        for (Method mm : rc.getDeclaredMethods()) {
                            if ("a".equals(mm.getName())) {
                                XposedBridge.log(TAG + ": f() RETURN method a "
                                        + mm.toGenericString());
                            }
                        }

                        installCandidateHookExact(rc);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": candidate discovery failed: "
                                + android.util.Log.getStackTraceString(t));
                    }
                }
            });

            XposedBridge.log(TAG + ": hooked hdl.f() class=" + fMethod.getDeclaringClass().getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hdl.f() hook failed: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private static synchronized void installCandidateHookExact(Class<?> runtimeClass) {
        installCandidateHookExact(runtimeClass, null);
    }

    private static synchronized void installCandidateHookExact(Class<?> runtimeClass, Object processor) {
        if (candidateHookInstalled || runtimeClass == null) return;

        try {
            Method target = null;
            Class<?> c = runtimeClass;

            while (c != null && target == null) {
                for (Method m : c.getDeclaredMethods()) {
                    if ("a".equals(m.getName())
                            && m.getParameterTypes().length == 0
                            && "oog".equals(m.getReturnType().getName())) {
                        target = m;
                        break;
                    }
                }
                c = c.getSuperclass();
            }

            if (target == null) {
                XposedBridge.log(TAG + ": exact hdb.a()Loog; not found on "
                        + runtimeClass.getName() + "; NO mutation performed");
                return;
            }

            target.setAccessible(true);
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam x) {
                    try {
                        Object result = x.getResult();
                        if (result == null) return;
                        Object candidateIndex = field(result, "m");
                        String candidatePinyin = readCandidatePinyin(processor, candidateIndex);
                        XposedBridge.log(TAG + ": CANDIDATE text="
                                + field(result, "a") + " index=" + candidateIndex
                                + " pinyin=" + candidatePinyin);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": candidate read failed: "
                                + android.util.Log.getStackTraceString(t));
                    }
                }
            });

            candidateHookInstalled = true;
            XposedBridge.log(TAG + ": hooked EXACT candidate method="
                    + target.getDeclaringClass().getName() + ".a()Loog;");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": exact candidate hook failed: "
                    + android.util.Log.getStackTraceString(t));
        }
    }

    private static synchronized String readCandidatePinyin(Object processor, Object candidateIndex) {
        if (!(candidateIndex instanceof Number) || processor == null) return "null";
        try {
            Object engine = candidateEngine;
            if (engine == null) {
                engine = findObjectByTypeName(processor, "HmmEngineInterfaceImpl", 4);
                if (engine == null) {
                    Object hdl = findFieldInHierarchy(processor, "m");
                    engine = findObjectByTypeName(hdl, "HmmEngineInterfaceImpl", 4);
                }
                if (engine != null) candidateEngine = engine;
            }
            if (engine == null) return "null";
            int index = ((Number) candidateIndex).intValue();
            Method countMethod = engine.getClass().getMethod("c", int.class);
            int count = ((Number) countMethod.invoke(engine, index)).intValue();
            if (count <= 0 || count > 64) return "null";
            Method tokenMethod = engine.getClass().getMethod("i", int.class, int.class);
            Method textMethod = engine.getClass().getMethod("u", long.class);
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < count; i++) {
                Object handle = tokenMethod.invoke(engine, index, i);
                if (!(handle instanceof Number)) return "null";
                Object token = textMethod.invoke(engine, ((Number) handle).longValue());
                if (token == null) return "null";
                if (s.length() > 0) s.append(' ');
                s.append(token);
            }
            return s.toString();
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        }
    }

    private static Object findObjectByTypeName(Object root, String simpleName, int maxDepth) {
        if (root == null || maxDepth < 0) return null;
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        return findObjectByTypeName(root, simpleName, maxDepth, seen);
    }

    private static Object findObjectByTypeName(
            Object root, String simpleName, int depth, java.util.IdentityHashMap<Object, Boolean> seen) {
        if (root == null || depth < 0) return null;
        Class<?> rootClass = root.getClass();
        if (simpleName.equals(rootClass.getSimpleName())) return root;
        if (seen.put(root, Boolean.TRUE) != null) return null;

        Class<?> c = rootClass;
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                int modifiers = f.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || f.getType().isPrimitive()
                        || f.getType().isEnum()
                        || f.getType() == String.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(root);
                    Object found = findObjectByTypeName(value, simpleName, depth - 1, seen);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object findFieldByTypeName(Object o, String simpleName) {
        if (o == null) return null;
        try {
            Class<?> c = o.getClass();
            while (c != null) {
                for (Field f : c.getDeclaredFields()) {
                    Class<?> type = f.getType();
                    if (type != null && simpleName.equals(type.getSimpleName())) {
                        f.setAccessible(true);
                        return f.get(o);
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
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
    
}
