package com.microllate.gboardword;

import android.content.Context;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class KeyboardLayoutHook {
    private static final String GBOARD = "com.google.android.inputmethod.latin";

    // Phone and wide layouts from the corresponding arm64 Gboard APKs.
    private static final int VERSION_18_3 = 176004286;
    private static final int VERSION_18_6 = 176071878;
    private static final int COMMA = 0x7f0b0002;
    private static final int PERIOD = 0x7f0b0008;

    private static int layoutVersion(int layout) {
        switch (layout) {
            case 0x7f0e01b4:
            case 0x7f0e01b9:
            case 0x7f0e03a1: // English QWERTY
            case 0x7f0e02db: // English QWERTY with number row
                return VERSION_18_3;
            case 0x7f0e01b7:
            case 0x7f0e01bc:
            case 0x7f0e03a5: // English QWERTY
            case 0x7f0e02df: // English QWERTY with number row
                return VERSION_18_6;
            default:
                return 0;
        }
    }

    static void install() {
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int layout = (int) param.args[0];
                            if (layoutVersion(layout) == 0) return;

                            ViewGroup parent = (ViewGroup) param.args[1];
                            boolean attach = parent != null && (boolean) param.args[2];
                            param.setObjectExtra("gboardWordLayoutIndex",
                                    attach ? parent.getChildCount() : -1);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Integer childIndex = (Integer) param.getObjectExtra(
                                    "gboardWordLayoutIndex");
                            if (childIndex == null || param.hasThrowable()) return;

                            LayoutInflater inflater = (LayoutInflater) param.thisObject;
                            try {
                                int version = layoutVersion((int) param.args[0]);
                                if (!isSupported(inflater.getContext(), version)) return;
                                View keyboard = childIndex < 0 ? (View) param.getResult()
                                        : ((ViewGroup) param.args[1]).getChildAt(childIndex);
                                if (keyboard == null) return;
                                boolean version18_3 = version == VERSION_18_3;
                                View space = keyboard.findViewById(
                                        version18_3 ? 0x7f0b05b1 : 0x7f0b05a2);
                                View globe = keyboard.findViewById(
                                        version18_3 ? 0x7f0b05b4 : 0x7f0b05a5);
                                widenGlobe(space, globe);
                                moveComma(keyboard, space);
                            } catch (RuntimeException | PackageManager.NameNotFoundException e) {
                                XposedBridge.log("GboardWord: keyboard layout adjustment failed: " + e);
                            }
                        }
                    });
        } catch (RuntimeException | LinkageError e) {
            XposedBridge.log("GboardWord: keyboard layout hook failed: " + e);
        }
    }

    @SuppressWarnings("deprecation")
    private static boolean isSupported(Context context, int version) throws PackageManager.NameNotFoundException {
        return GBOARD.equals(context.getPackageName())
                && context.getPackageManager().getPackageInfo(GBOARD, 0).versionCode
                        == version;
    }

    private static void widenGlobe(View space, View globe) {
        if (space == null || globe == null || space.getParent() != globe.getParent()
                || !(space.getParent() instanceof LinearLayout)
                || !(space.getLayoutParams() instanceof LinearLayout.LayoutParams)
                || !(globe.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;

        LinearLayout row = (LinearLayout) space.getParent();
        if (row.getOrientation() != LinearLayout.HORIZONTAL
                || row.indexOfChild(globe) >= row.indexOfChild(space)) return;

        LinearLayout.LayoutParams spaceParams = (LinearLayout.LayoutParams) space.getLayoutParams();
        LinearLayout.LayoutParams globeParams = (LinearLayout.LayoutParams) globe.getLayoutParams();
        // Transfer a quarter of the globe key's original weight from the spacebar.
        float extra = globeParams.weight * 0.25f;
        if (extra <= 0 || spaceParams.weight <= extra) return;

        spaceParams.weight -= extra;
        globeParams.weight += extra;
        row.requestLayout();
    }

    private static void moveComma(View keyboard, View space) {
        View comma = keyboard.findViewById(COMMA);
        View period = keyboard.findViewById(PERIOD);
        if (comma == null || period == null || space == null
                || !(comma.getParent() instanceof LinearLayout)
                || comma.getParent() != period.getParent()) return;

        LinearLayout row = (LinearLayout) comma.getParent();
        if (row.getOrientation() != LinearLayout.HORIZONTAL) return;

        // The wide layout puts the spacebar inside a container in the same row.
        View spaceInRow = space;
        if (space.getParent() != row && space.getParent() instanceof View) {
            spaceInRow = (View) space.getParent();
        }
        if (spaceInRow.getParent() != row) return;

        int commaIndex = row.indexOfChild(comma);
        int spaceIndex = row.indexOfChild(spaceInRow);
        int periodIndex = row.indexOfChild(period);
        if (commaIndex >= spaceIndex || spaceIndex >= periodIndex) return;

        ViewGroup.LayoutParams params = comma.getLayoutParams();
        row.removeViewAt(commaIndex);
        row.addView(comma, periodIndex - 1, params);
        XposedBridge.log("GboardWord: comma moved");
    }
}
