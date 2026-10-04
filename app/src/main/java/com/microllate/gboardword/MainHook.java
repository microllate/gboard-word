package com.microllate.gboardword;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

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
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD.equals(lpparam.packageName)) return;

        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> processorClass = XposedHelpers.findClass(PROCESSOR, cl);
            int hooked = 0;

            for (java.lang.reflect.Method method : processorClass.getDeclaredMethods()) {
                if (!"Z".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2 || params[1] != boolean.class) continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object candidate = param.args[0];
                        StringBuilder sb = new StringBuilder(TAG + ": Z() oog dump");

                        if (candidate == null) {
                            sb.append(" <null>");
                        } else {
                            Class<?> c = candidate.getClass();
                            sb.append(" class=").append(c.getName());

                            for (Field field : c.getDeclaredFields()) {
                                if (Modifier.isStatic(field.getModifiers())) continue;
                                try {
                                    field.setAccessible(true);
                                    Object value = field.get(candidate);
                                    sb.append(" | ")
                                      .append(field.getName())
                                      .append(":")
                                      .append(field.getType().getName())
                                      .append("=")
                                      .append(String.valueOf(value));
                                } catch (Throwable t) {
                                    sb.append(" | ")
                                      .append(field.getName())
                                      .append(":<error>");
                                }
                            }
                        }

                        sb.append(" | arg1=").append(param.args[1]);
                        XposedBridge.log(sb.toString());
                    }
                });
                hooked++;
            }

            XposedBridge.log(TAG + ": hooked Z candidate selection methods=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook install failed: " + android.util.Log.getStackTraceString(t));
        }
    }
}
