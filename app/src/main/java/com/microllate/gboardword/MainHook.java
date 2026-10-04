package com.microllate.gboardword;

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
                        XposedBridge.log(TAG + ": Z() CALLED, arg0="
                                + (param.args[0] == null ? "null" : param.args[0].getClass().getName())
                                + ", arg1=" + param.args[1]);
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
