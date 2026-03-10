package com.example.bilibilisponsorblock;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;

/**
 * 莫奈取色工具类 - Material You 动态颜色
 * 
 * 方案一：使用 Android 12+ (API 31+) 系统原生动态颜色
 * 如果系统不支持，则使用 Material Design 3 默认配色
 */
public class MonetColorUtils {

    private static final String TAG = "MonetColorUtils";
    
    // 缓存的颜色值
    private static int cachedPrimaryColor = 0;
    private static long lastUpdateTime = 0;
    private static final long CACHE_DURATION = 60000; // 缓存1分钟
    
    /**
     * 获取主色调 (Primary)
     * Android 12+: 使用系统动态颜色
     * Android 11-: 使用 Material Design 3 默认紫色
     */
    public static int getMonetPrimaryColor(Context context) {
        if (shouldUseCache() && cachedPrimaryColor != 0) {
            return cachedPrimaryColor;
        }
        
        int color = getSystemDynamicColor(context, "system_accent1_500");
        if (color != 0) {
            cachedPrimaryColor = color;
            lastUpdateTime = System.currentTimeMillis();
            LogUtils.getInstance().log(TAG, "使用系统动态主色: #" + String.format("%06X", color));
            return color;
        }
        
        // 回退到默认颜色
        LogUtils.getInstance().logDebug(TAG, "使用默认主色");
        return 0xFF6750A4; // Material You 默认紫色
    }
    
    /**
     * 获取次要色调 (Secondary)
     */
    public static int getMonetSecondaryColor(Context context) {
        int color = getSystemDynamicColor(context, "system_accent2_500");
        if (color != 0) return color;
        
        // 回退：基于主色生成
        int primary = getMonetPrimaryColor(context);
        float[] hsv = new float[3];
        Color.colorToHSV(primary, hsv);
        hsv[1] = Math.max(0, hsv[1] - 0.15f);
        hsv[2] = Math.min(1, hsv[2] + 0.05f);
        return Color.HSVToColor(hsv);
    }
    
    /**
     * 获取第三色调 (Tertiary)
     */
    public static int getMonetTertiaryColor(Context context) {
        int color = getSystemDynamicColor(context, "system_accent3_500");
        if (color != 0) return color;
        
        // 回退：基于主色偏移色相
        int primary = getMonetPrimaryColor(context);
        float[] hsv = new float[3];
        Color.colorToHSV(primary, hsv);
        hsv[0] = (hsv[0] + 60) % 360; // 色相偏移60度
        return Color.HSVToColor(hsv);
    }
    
    /**
     * 获取表面色 (Surface - 最浅)
     */
    public static int getMonetSurfaceColor(Context context) {
        int color = getSystemDynamicColor(context, "system_neutral1_50");
        if (color != 0) return color;
        return 0xFFFEF7FF; // 默认表面色
    }
    
    /**
     * 获取表面变体色 (Surface Variant)
     */
    public static int getMonetSurfaceVariantColor(Context context) {
        int color = getSystemDynamicColor(context, "system_neutral2_100");
        if (color != 0) return color;
        return 0xFFE7E0EC; // 默认表面变体色
    }
    
    /**
     * 获取背景色 (Background)
     */
    public static int getMonetBackgroundColor(Context context) {
        int color = getSystemDynamicColor(context, "system_neutral1_10");
        if (color != 0) return color;
        return 0xFFFFFBFE; // 默认背景色
    }
    
    /**
     * 获取容器色 (Primary Container)
     */
    public static int getMonetContainerColor(Context context) {
        int color = getSystemDynamicColor(context, "system_accent1_100");
        if (color != 0) return color;
        
        // 回退：基于主色生成
        int primary = getMonetPrimaryColor(context);
        float[] hsv = new float[3];
        Color.colorToHSV(primary, hsv);
        hsv[1] = 0.15f;
        hsv[2] = 0.93f;
        return Color.HSVToColor(hsv);
    }
    
    /**
     * 获取轮廓色 (Outline)
     */
    public static int getMonetOutlineColor(Context context) {
        int color = getSystemDynamicColor(context, "system_neutral2_500");
        if (color != 0) return color;
        return 0xFF79747E; // 默认轮廓色
    }
    
    /**
     * 获取错误色 (Error)
     */
    public static int getMonetErrorColor(Context context) {
        // 错误色通常不使用动态颜色，使用标准红色
        return 0xFFB3261E;
    }
    
    /**
     * 获取主色上的文字颜色 (On Primary)
     */
    public static int getMonetOnPrimaryColor(Context context) {
        return 0xFFFFFFFF; // 白色
    }
    
    /**
     * 获取表面上的文字颜色 (On Surface)
     */
    public static int getMonetOnSurfaceColor(Context context) {
        return 0xFF1C1B1F; // 深灰色
    }
    
    /**
     * 获取系统动态颜色
     * @param colorName 颜色资源名称，如 "system_accent1_500"
     * @return 颜色值，如果不支持则返回 0
     */
    private static int getSystemDynamicColor(Context context, String colorName) {
        // Android 12+ (API 31+) 才支持系统动态颜色
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return 0;
        }
        
        try {
            Resources res = context.getResources();
            int resId = res.getIdentifier(colorName, "color", "android");
            if (resId != 0) {
                return context.getColor(resId);
            }
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "获取系统颜色失败: " + colorName + " - " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * 是否应该使用缓存
     */
    private static boolean shouldUseCache() {
        return (System.currentTimeMillis() - lastUpdateTime) < CACHE_DURATION;
    }
    
    /**
     * 清除缓存
     */
    public static void clearCache() {
        cachedPrimaryColor = 0;
        lastUpdateTime = 0;
    }
    
    /**
     * 检查是否支持 Material You 动态颜色
     */
    public static boolean isDynamicColorSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }
}
