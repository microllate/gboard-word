package com.microllate.gboardword;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
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

    private static final Object DICTIONARY_LOCK = new Object();
    private static final Set<String> IMPORTED_WORDS = new HashSet<>();
    private static Object cachedDb;
    private static Object cachedImporter;
    private static Method cachedImportMethod;
    private static boolean dictionaryCacheInitialized;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!GBOARD.equals(p.packageName)) return;

        KeyboardLayoutHook.install();

        try {
            Class<?> pc = XposedHelpers.findClass(PROCESSOR, p.classLoader);

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

                            if (!Boolean.TRUE.equals(fullMatch)
                                    || phrase == null || phrase.length() <= 1
                                    || tokens == null) {
                                return;
                            }

                            String pinyin = shortcutFromTokens(tokens);
                            if (!pinyin.isEmpty()) importDictionaryThroughGboard(phrase, pinyin);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": SELECT failed: "
                                    + t.getClass().getSimpleName() + ": "
                                    + String.valueOf(t.getMessage()));
                        }
                    }
                });
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook install failed: "
                    + t.getClass().getSimpleName());
        }
    }

    /**
     * Build the exact TXT content accepted by Gboard's Personal Dictionary importer.
     */
    private static String buildDictionaryText(String phrase, String pinyin) {
        return "# Gboard Dictionary version:2\n"
                + "# Gboard Dictionary format:shortcut\tword\tlanguage_tag\tpos_tag\n"
                + pinyin + "\t" + phrase + "\tzh-CN\t\n";
    }

    /** Called under DICTIONARY_LOCK. Publish the cache only after a complete scan. */
    private static void initializeDictionaryCache(Application app) throws Exception {
        if (dictionaryCacheInitialized) return;

        ClassLoader loader = app.getClassLoader();
        Class<?> dbClass = XposedHelpers.findClass(PERSONAL_DICTIONARY_DB, loader);
        cachedDb = dbClass.getConstructor(Context.class).newInstance(app);
        Object result = dbClass.getMethod("c").invoke(cachedDb);
        if (!(result instanceof Cursor)) {
            throw new IllegalStateException("dictionary query did not return Cursor");
        }
        Set<String> words = new HashSet<>();
        try (Cursor cursor = (Cursor) result) {
            int wordIndex = cursor.getColumnIndexOrThrow("word");
            while (cursor.moveToNext()) {
                String word = cursor.getString(wordIndex);
                if (word != null) words.add(word);
            }
        }
        IMPORTED_WORDS.addAll(words);
        dictionaryCacheInitialized = true;
    }

    private static void importDictionaryThroughGboard(String phrase, String pinyin) {
        synchronized (DICTIONARY_LOCK) {
            if (IMPORTED_WORDS.contains(phrase)) return;

            try {
                Application app = (Application) XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication");
                if (app == null) throw new IllegalStateException("application=null");
                initializeDictionaryCache(app);
                if (IMPORTED_WORDS.contains(phrase)) return;

                if (cachedImporter == null) {
                    ClassLoader loader = app.getClassLoader();
                    Class<?> importerClass = XposedHelpers.findClass(
                            PERSONAL_DICTIONARY_IMPORTER, loader);
                    Method importMethod = importerClass.getDeclaredMethod(
                            "a", java.io.InputStream.class, String.class);
                    importMethod.setAccessible(true);
                    cachedImporter = importerClass.getConstructor(
                            XposedHelpers.findClass("qhc", loader)).newInstance(cachedDb);
                    cachedImportMethod = importMethod;
                }

                byte[] text = buildDictionaryText(phrase, pinyin).getBytes(StandardCharsets.UTF_8);
                Object result = cachedImportMethod.invoke(
                        cachedImporter, new ByteArrayInputStream(text), "text/plain");
                if (result == null || XposedHelpers.getIntField(result, "a") != 1) {
                    throw new IllegalStateException("importer did not accept the dictionary entry");
                }
                IMPORTED_WORDS.add(phrase);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                        && ((java.lang.reflect.InvocationTargetException) t).getCause() != null
                        ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
                if (cachedDb instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) cachedDb).close();
                    } catch (Exception closeFailure) {
                        cause.addSuppressed(closeFailure);
                    }
                }
                cachedDb = null;
                cachedImporter = null;
                cachedImportMethod = null;
                dictionaryCacheInitialized = false;
                IMPORTED_WORDS.clear();
                XposedBridge.log(TAG + ": IMPORT FAILED " + cause.getClass().getSimpleName()
                        + ": " + String.valueOf(cause.getMessage()));
            }
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
