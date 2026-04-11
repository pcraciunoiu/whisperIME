package com.whispertflite.utils;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.WindowInsetsController;

public class ThemeUtils {

    public static void setStatusBarAppearance(Activity activity){
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Must run after setContentView: DecorView is null until then, and
            // PhoneWindow.getInsetsController() NPEs on API 35+ (e.g. Pixel Android 15).
            if (activity.getWindow().getDecorView() == null) {
                return;
            }
            int nightModeFlags = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean isDarkMode = (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);
            WindowInsetsController insetsController = activity.getWindow().getInsetsController();
            if (insetsController != null) {
                if (isDarkMode) {
                    // Dark mode: remove light status bar appearance (use light icons)
                    insetsController.setSystemBarsAppearance(
                            0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    );
                } else {
                    // Light mode: enable light status bar appearance (dark icons)
                    insetsController.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    );
                }
            }
        }
    }
}
