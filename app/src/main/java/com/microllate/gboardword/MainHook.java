package com.microllate.gboardword;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
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
    private static final String PERSONAL_DICTIONARY_IMPORTER = "qhm";
    private static final String PERSONAL_DICTIONARY_DB = "qhf";

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
                                    || phrase == null || phrase.length() <= 1
                                    || tokens == null || pinyin.isEmpty()) {
                                return;
                            }

                            XposedBridge.log(TAG + ": SELECT phrase=" + phrase
                                    + " pinyin=" + pinyin);

                            String dictionaryText = buildDictionaryText(phrase, pinyin);
                            importDictionaryThroughGboard(dictionaryText);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": SELECT failed: "
                                    + t.getClass().getSimpleName() + ": "
                                    + String.valueOf(t.getMessage()));
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

    /**
     * Generate the exact TXT format accepted by Gboard's Personal Dictionary
     * importer and return the same content Uri that a file picker would give
     * to Gboard.
     */
    /**
     * Build the exact TXT content accepted by Gboard's Personal Dictionary
     * importer, but keep it entirely in memory instead of creating a file.
     */
    private static String buildDictionaryText(String phrase, String pinyin) {
        return "# Gboard Dictionary version:2\\n"
                + "# Gboard Dictionary format:shortcut\\tword\\tlanguage_tag\\tpos_tag\\n"
                + pinyin + "\\t" + phrase + "\\tzh-CN\\t\\n";
    }

    /**
     * Run Gboard's own PersonalDictionaryImporter core directly. The UI
     * fragment is only the front-end; qhm parses the TXT and qhf persists
     * the resulting qgw entry into PersonalDictionary.db.
     */
    private static void importDictionaryThroughGboard(String dictionaryText) {
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"),
                    "currentApplication");
            if (app == null) {
                throw new IllegalStateException("application=null");
            }

            ClassLoader loader = app.getClassLoader();
            Class<?> dbClass = XposedHelpers.findClass(PERSONAL_DICTIONARY_DB, loader);
            Object db = dbClass.getConstructor(android.content.Context.class).newInstance(app);

            Class<?> importerClass = XposedHelpers.findClass(PERSONAL_DICTIONARY_IMPORTER, loader);
            Object importer = importerClass.getConstructor(
                    XposedHelpers.findClass("qhc", loader)).newInstance(db);

            try (java.io.InputStream in = new java.io.ByteArrayInputStream(
                        dictionaryText.getBytes(StandardCharsets.UTF_8))) {
                Method importMethod = importerClass.getDeclaredMethod(
                        "a", java.io.InputStream.class, String.class);
                importMethod.setAccessible(true);
                Object result = importMethod.invoke(importer, in, "text/plain");

                XposedBridge.log(TAG + ": IMPORT dispatched through Gboard PersonalDictionaryImporter"
                        + " result=" + String.valueOf(result));
            }
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                    && ((java.lang.reflect.InvocationTargetException) t).getCause() != null
                    ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
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
