package com.microllate.gboardword;

import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.app.Activity;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.OutputStream;
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
    private static final String PERSONAL_DICTIONARY_FRAGMENT =
            "com.google.android.libraries.inputmethod.personaldictionary.preference.PersonalDictionaryWordsFragment";

    private static volatile Object personalDictionaryFragment;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!GBOARD.equals(p.packageName)) return;

        try {
            Class<?> fragmentClass = XposedHelpers.findClass(
                    PERSONAL_DICTIONARY_FRAGMENT, p.classLoader);
            XposedHelpers.findAndHookConstructor(fragmentClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam x) {
                    personalDictionaryFragment = x.thisObject;
                    XposedBridge.log(TAG + ": PersonalDictionaryWordsFragment ready");
                }
            });

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

                            Uri uri = generateDictionaryTxt(phrase, pinyin);
                            if (uri != null) {
                                importDictionaryThroughGboard(uri);
                            }
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
    private static synchronized Uri generateDictionaryTxt(
            String phrase, String pinyin) {
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"),
                    "currentApplication");
            if (app == null) {
                XposedBridge.log(TAG + ": TXT failed: application=null");
                return null;
            }

            String dictionaryText =
                    "# Gboard Dictionary version:2\n"
                    + "# Gboard Dictionary format:shortcut\tword\tlanguage_tag\tpos_tag\n"
                    + pinyin + "\t" + phrase + "\tzh-CN\t\n";

            ContentResolver resolver = app.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "gboard_word_dictionary.txt");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/GboardWord");

            Uri uri = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert returned null");
            }

            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) {
                    throw new IllegalStateException("openOutputStream returned null");
                }
                out.write(dictionaryText.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            XposedBridge.log(TAG + ": TXT generated: "
                    + "Download/GboardWord/gboard_word_dictionary.txt"
                    + " phrase=" + phrase
                    + " shortcut=" + pinyin);
            return uri;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": TXT FAILED "
                    + t.getClass().getSimpleName() + ": "
                    + String.valueOf(t.getMessage()));
            return null;
        }
    }

    /**
     * Re-enter Gboard's own Personal Dictionary import result handler.
     * This is the same X(2, RESULT_OK, intent) path used after the manual
     * document picker returns a selected TXT file.
     */
    private static void importDictionaryThroughGboard(Uri uri) {
        Object fragment = personalDictionaryFragment;
        if (fragment == null) {
            XposedBridge.log(TAG + ": IMPORT skipped: PersonalDictionaryWordsFragment not ready");
            return;
        }

        try {
            Intent intent = new Intent();
            intent.setData(uri);

            Method method = null;
            Class<?> c = fragment.getClass();
            while (c != null && method == null) {
                try {
                    method = c.getDeclaredMethod(
                            "X", int.class, int.class, Intent.class);
                } catch (NoSuchMethodException e) {
                    c = c.getSuperclass();
                }
            }

            if (method == null) {
                throw new NoSuchMethodException("X(int,int,Intent)");
            }

            method.setAccessible(true);
            method.invoke(fragment, 2, Activity.RESULT_OK, intent);

            XposedBridge.log(TAG + ": IMPORT dispatched through PersonalDictionaryWordsFragment.X"
                    + " uri=" + uri);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": IMPORT FAILED "
                    + t.getClass().getSimpleName() + ": "
                    + String.valueOf(t.getMessage()));
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
