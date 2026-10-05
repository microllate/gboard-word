package com.microllate.gboardword;

import android.app.Application;
import android.content.Context;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
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

                        Object selected = findField(x.thisObject, "O");
                        if (selected == null
                                || !"hcv".equals(selected.getClass().getSimpleName())) {
                            return;
                        }

                        try {
                            Object phraseObject = findField(selected, "a");
                            Object tokens = findField(selected, "b");
                            Object fullMatch = findField(selected, "e");

                            String phrase = phraseObject == null
                                    ? null : String.valueOf(phraseObject);
                            String pinyin = shortcutFromTokens(tokens);

                            if (!Boolean.TRUE.equals(fullMatch)
                                    || phrase == null || phrase.isEmpty()
                                    || tokens == null || pinyin.isEmpty()) {
                                return;
                            }

                            XposedBridge.log(TAG + ": SELECT phrase=" + phrase
                                    + " pinyin=" + pinyin);

                            importToGboardPersonalDictionary(
                                    x.thisObject.getClass().getClassLoader(),
                                    tokens,
                                    phrase);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": SELECT failed: "
                                    + t.getClass().getSimpleName());
                        }
                    }
                });
                hooked++;
            }

            XposedBridge.log(TAG + ": hook ready methods=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook install failed: "
                    + t.getClass().getSimpleName());
        }
    }

    private static synchronized void importToGboardPersonalDictionary(
            ClassLoader loader, Object tokens, String phrase) {
        try {
            String pinyin = shortcutFromTokens(tokens);
            if (pinyin == null || pinyin.isEmpty()
                    || phrase == null || phrase.isEmpty()) {
                return;
            }

            Application app = (Application) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"),
                    "currentApplication");
            if (app == null) {
                XposedBridge.log(TAG + ": import failed: application=null");
                return;
            }

            Class<?> qhfClass = Class.forName("qhf", false, loader);
            Constructor<?> qhfConstructor =
                    qhfClass.getDeclaredConstructor(Context.class);
            qhfConstructor.setAccessible(true);
            Object qhc = qhfConstructor.newInstance(app);

            Class<?> carClass = Class.forName("car", false, loader);
            Constructor<?> carConstructor = carClass.getDeclaredConstructor();
            carConstructor.setAccessible(true);
            Object car = carConstructor.newInstance();

            Class<?> qhmClass = Class.forName("qhm", false, loader);
            Constructor<?> importerConstructor = null;
            for (Constructor<?> constructor : qhmClass.getDeclaredConstructors()) {
                Class<?>[] ps = constructor.getParameterTypes();
                if (ps.length == 1 && ps[0].isAssignableFrom(qhc.getClass())) {
                    importerConstructor = constructor;
                    break;
                }
            }
            if (importerConstructor == null) {
                throw new NoSuchMethodException("qhm(qhc)");
            }

            importerConstructor.setAccessible(true);
            Object importer = importerConstructor.newInstance(qhc);

            String dictionaryText =
                    "# Gboard Dictionary version:2\n"
                    + "# Gboard Dictionary format:shortcut\tword\tlanguage_tag\tpos_tag\n"
                    + pinyin + "\t" + phrase + "\tzh-CN\t\n";

            InputStream input = new ByteArrayInputStream(
                    dictionaryText.getBytes(StandardCharsets.UTF_8));

            Method parse = null;
            Class<?> c = qhmClass;
            while (c != null && parse == null) {
                for (Method method : c.getDeclaredMethods()) {
                    Class<?>[] ps = method.getParameterTypes();
                    if ("a".equals(method.getName())
                            && ps.length == 2
                            && InputStream.class.isAssignableFrom(ps[0])
                            && ps[1] == String.class) {
                        parse = method;
                        break;
                    }
                }
                c = c.getSuperclass();
            }
            if (parse == null) {
                throw new NoSuchMethodException("qhm.a(InputStream,String)");
            }

            parse.setAccessible(true);
            Object parsed = parse.invoke(importer, input, "dictionary.txt");
            if (parsed == null) {
                throw new IllegalStateException("qhm returned null");
            }

            Method importMethod = null;
            c = carClass;
            while (c != null && importMethod == null) {
                for (Method method : c.getDeclaredMethods()) {
                    Class<?>[] ps = method.getParameterTypes();
                    if ("k".equals(method.getName())
                            && ps.length == 1
                            && ps[0].isAssignableFrom(parsed.getClass())) {
                        importMethod = method;
                        break;
                    }
                }
                c = c.getSuperclass();
            }
            if (importMethod == null) {
                throw new NoSuchMethodException("car.k(qhl)");
            }

            importMethod.setAccessible(true);
            importMethod.invoke(car, parsed);

            XposedBridge.log(TAG + ": IMPORTED phrase=" + phrase
                    + " shortcut=" + pinyin);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            XposedBridge.log(TAG + ": IMPORT FAILED "
                    + cause.getClass().getSimpleName() + ": "
                    + String.valueOf(cause.getMessage()));
        }
    }

    private static String join(Object value) {
        if (value == null) return "";

        if (value instanceof Object[]) {
            Object[] values = (Object[]) value;
            StringBuilder result = new StringBuilder();
            for (Object item : values) {
                if (item == null) continue;
                if (result.length() > 0) result.append(' ');
                result.append(item);
            }
            return result.toString();
        }

        return String.valueOf(value);
    }

    private static String shortcutFromTokens(Object value) {
        String pinyin = join(value);
        if (pinyin.isEmpty()) return "";

        StringBuilder result = new StringBuilder();
        String[] syllables = pinyin.trim().split("\\s+");
        for (String syllable : syllables) {
            if (syllable.isEmpty()) continue;
            if ("sh".equals(syllable)) syllable = "shi";
            else if ("ch".equals(syllable)) syllable = "chi";
            else if ("zh".equals(syllable)) syllable = "zhi";
            result.append(syllable);
        }
        return result.toString();
    }

    private static Object findField(Object object, String name) {
        if (object == null) return null;

        Class<?> c = object.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }
}
