package com.microllate.gboardword;

import android.util.Log;

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
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!GBOARD.equals(lpparam.packageName)) return;

        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> processorClass = XposedHelpers.findClass(PROCESSOR, cl);
            int hooked = 0;

            for (Method method : processorClass.getDeclaredMethods()) {
                if (!"Z".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2 || params[1] != boolean.class) continue;

                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object candidate = param.args[0];
                            boolean flag = (Boolean) param.args[1];
                            Object text = readField(candidate, "a");
                            Object index = readField(candidate, "m");
                            Log.i(TAG, "candidate selected: text=" + text + ", index=" + index + ", flag=" + flag);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": observation failed: " + Log.getStackTraceString(t));
                        }
                    }
                });
                hooked++;
            }

            XposedBridge.log(TAG + ": hooked Z candidate selection methods=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook install failed: " + Log.getStackTraceString(t));
        }
    }

    private static Object readField(Object obj, String name) throws Exception {
        if (obj == null) return null;
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }
}
