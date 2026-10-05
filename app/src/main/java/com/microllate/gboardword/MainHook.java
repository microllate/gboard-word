package com.microllate.gboardword;

import android.app.Application;
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
    private static boolean dictionaryCacheInitialized;

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

                            importDictionaryThroughGboard(phrase, pinyin);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": SELECT failed: "
                                    + t.getClass().getSimpleName() + ": "
                                    + String.valueOf(t.getMessage()));
                        }
                    }
                });
                hooked++;
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook install failed: "
                    + t.getClass().getSimpleName());
        }
    }

    private static final String DICTIONARY_FILE =
            "/data/user/0/com.google.android.inputmethod.latin/files/gboard_word_dictionary.txt";

    /**
     * Build the exact TXT content accepted by Gboard's Personal Dictionary importer.
     */
    private static String buildDictionaryText(String phrase, String pinyin) {
        return "# Gboard Dictionary version:2\n"
                + "# Gboard Dictionary format:shortcut\tword\tlanguage_tag\tpos_tag\n"
                + pinyin + "\t" + phrase + "\tzh-CN\t\n";
    }

    /**
     * Load the existing personal dictionary once and keep the words in memory.
     */
    private static boolean alreadyImported(String phrase, ClassLoader loader,
            Application app) throws Exception {
        synchronized (DICTIONARY_LOCK) {
            if (!dictionaryCacheInitialized) {
                Class<?> dbClass = XposedHelpers.findClass(PERSONAL_DICTIONARY_DB, loader);
                cachedDb = dbClass.getConstructor(android.content.Context.class).newInstance(app);
                Method query = dbClass.getMethod("c");
                Object cursorObject = query.invoke(cachedDb);
                if (!(cursorObject instanceof android.database.Cursor)) {
                    throw new IllegalStateException("dictionary query did not return Cursor");
                }
                android.database.Cursor cursor = (android.database.Cursor) cursorObject;
                try {
                    int wordIndex = cursor.getColumnIndex("word");
                    if (wordIndex < 0) throw new IllegalStateException("word column not found");
                    while (cursor.moveToNext()) {
                        String word = cursor.getString(wordIndex);
                        if (word != null) IMPORTED_WORDS.add(word);
                    }
                } finally {
                    cursor.close();
                }
                dictionaryCacheInitialized = true;
            }
            return IMPORTED_WORDS.contains(phrase);
        }
    }

    /**
     * Reuse Gboard's DB/importer objects instead of creating them for every word.
     */
    private static void importDictionaryThroughGboard(String phrase, String pinyin) {
        synchronized (DICTIONARY_LOCK) {
            try {
                Application app = (Application) XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"), "currentApplication");
                if (app == null) throw new IllegalStateException("application=null");
                ClassLoader loader = app.getClassLoader();
                if (alreadyImported(phrase, loader, app)) return;

                String dictionaryText = buildDictionaryText(phrase, pinyin);
                java.io.File file = new java.io.File(DICTIONARY_FILE);
                java.io.File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                    throw new java.io.IOException("cannot create parent directory");
                }
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file, false)) {
                    out.write(dictionaryText.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }

                if (cachedDb == null || cachedImporter == null) {
                    Class<?> dbClass = XposedHelpers.findClass(PERSONAL_DICTIONARY_DB, loader);
                    cachedDb = dbClass.getConstructor(android.content.Context.class).newInstance(app);
                    Class<?> importerClass = XposedHelpers.findClass(PERSONAL_DICTIONARY_IMPORTER, loader);
                    cachedImporter = importerClass.getConstructor(
                            XposedHelpers.findClass("qhc", loader)).newInstance(cachedDb);
                }

                Class<?> importerClass = cachedImporter.getClass();
                try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                    Method importMethod = importerClass.getDeclaredMethod(
                            "a", java.io.InputStream.class, String.class);
                    importMethod.setAccessible(true);
                    importMethod.invoke(cachedImporter, in, "text/plain");
                }
                IMPORTED_WORDS.add(phrase);
            } catch (Throwable t) {
                Throwable cause = t instanceof java.lang.reflect.InvocationTargetException
                        && ((java.lang.reflect.InvocationTargetException) t).getCause() != null
                        ? ((java.lang.reflect.InvocationTargetException) t).getCause() : t;
                cachedDb = null;
                cachedImporter = null;
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
