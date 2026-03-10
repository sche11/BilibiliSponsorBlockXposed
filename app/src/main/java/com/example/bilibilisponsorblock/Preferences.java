package com.example.bilibilisponsorblock;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 设置管理类
 * 统一管理空降助手的所有配置选项
 */
public class Preferences {

    public static final String PREFS_NAME = "sponsorblock_prefs";

    // XSharedPreferences 用于 Xposed 环境
    private static XSharedPreferences xSharedPreferences;
    // 普通 SharedPreferences 用于 UI
    private static SharedPreferences sharedPreferences;
    private static Context appContext;

    // 设置键名
    public static final String KEY_MODULE_ENABLED = "module_enabled";
    public static final String KEY_SHOW_TOAST = "show_toast";
    public static final String KEY_API_SERVER = "api_server";
    public static final String KEY_SKIP_SPONSOR = "skip_sponsor";
    public static final String KEY_SKIP_SELF_PROMO = "skip_selfpromo";
    public static final String KEY_SKIP_INTRO = "skip_intro";
    public static final String KEY_SKIP_OUTRO = "skip_outro";
    public static final String KEY_SKIP_INTERACTION = "skip_interaction";
    public static final String KEY_SKIP_PREVIEW = "skip_preview";
    public static final String KEY_SKIP_FILLER = "skip_filler";
    public static final String KEY_SKIP_MUSIC_OFFTOPIC = "skip_music_offtopic";
    public static final String KEY_TEST_MODE = "test_mode"; // 测试模式

    // 空降助手 API 默认服务器 (来自 BiliPai 项目)
    public static final String DEFAULT_API_SERVER = "https://bsbsb.top/api";

    /**
     * 初始化（在 Xposed 环境中调用）
     */
    public static void initXposed() {
        try {
            xSharedPreferences = new XSharedPreferences("com.example.bilibilisponsorblock", PREFS_NAME);
            xSharedPreferences.makeWorldReadable();
        } catch (Exception e) {
            // XSharedPreferences 初始化失败
        }
    }

    /**
     * 初始化（在普通 Android 环境中调用）
     */
    public static void init(Context context) {
        appContext = context.getApplicationContext();
        if (sharedPreferences == null) {
            sharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        }
    }

    /**
     * 获取 SharedPreferences
     */
    private static SharedPreferences getPrefs() {
        if (sharedPreferences != null) {
            return sharedPreferences;
        }
        if (xSharedPreferences != null) {
            xSharedPreferences.reload();
            return xSharedPreferences;
        }
        return null;
    }

    /**
     * 模块是否启用
     */
    public static boolean isModuleEnabled() {
        SharedPreferences prefs = getPrefs();
        return prefs != null && prefs.getBoolean(KEY_MODULE_ENABLED, true);
    }

    /**
     * 是否显示 Toast 提示
     */
    public static boolean showToast() {
        SharedPreferences prefs = getPrefs();
        return prefs != null && prefs.getBoolean(KEY_SHOW_TOAST, true);
    }

    /**
     * 获取 API 服务器地址
     */
    public static String getApiServer() {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return DEFAULT_API_SERVER;
        return prefs.getString(KEY_API_SERVER, DEFAULT_API_SERVER);
    }

    /**
     * 获取启用的跳过类别
     */
    public static Set<String> getSkipCategories() {
        Set<String> categories = new HashSet<>();
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return categories;

        if (prefs.getBoolean(KEY_SKIP_SPONSOR, true)) categories.add("sponsor");
        if (prefs.getBoolean(KEY_SKIP_SELF_PROMO, true)) categories.add("selfpromo");
        if (prefs.getBoolean(KEY_SKIP_INTRO, true)) categories.add("intro");
        if (prefs.getBoolean(KEY_SKIP_OUTRO, true)) categories.add("outro");
        if (prefs.getBoolean(KEY_SKIP_INTERACTION, false)) categories.add("interaction");
        if (prefs.getBoolean(KEY_SKIP_PREVIEW, false)) categories.add("preview");
        if (prefs.getBoolean(KEY_SKIP_FILLER, false)) categories.add("filler");
        if (prefs.getBoolean(KEY_SKIP_MUSIC_OFFTOPIC, false)) categories.add("music_offtopic");

        return categories;
    }

    // 各类别的单独设置
    public static boolean shouldSkipSponsor() {
        SharedPreferences prefs = getPrefs();
        return prefs == null || prefs.getBoolean(KEY_SKIP_SPONSOR, true);
    }

    public static boolean shouldSkipSelfPromo() {
        SharedPreferences prefs = getPrefs();
        return prefs == null || prefs.getBoolean(KEY_SKIP_SELF_PROMO, true);
    }

    public static boolean shouldSkipIntro() {
        SharedPreferences prefs = getPrefs();
        return prefs == null || prefs.getBoolean(KEY_SKIP_INTRO, true);
    }

    public static boolean shouldSkipOutro() {
        SharedPreferences prefs = getPrefs();
        return prefs == null || prefs.getBoolean(KEY_SKIP_OUTRO, true);
    }

    public static boolean shouldSkipInteraction() {
        SharedPreferences prefs = getPrefs();
        return prefs != null && prefs.getBoolean(KEY_SKIP_INTERACTION, false);
    }

    public static boolean shouldSkipPreview() {
        SharedPreferences prefs = getPrefs();
        return prefs != null && prefs.getBoolean(KEY_SKIP_PREVIEW, false);
    }

    public static boolean shouldSkipFiller() {
        SharedPreferences prefs = getPrefs();
        return prefs != null && prefs.getBoolean(KEY_SKIP_FILLER, false);
    }

    public static boolean shouldSkipMusicOfftopic() {
        SharedPreferences prefs = getPrefs();
        return prefs != null && prefs.getBoolean(KEY_SKIP_MUSIC_OFFTOPIC, false);
    }

    // ========== 跳过模式设置 ==========

    /**
     * 获取指定类别的跳过模式
     * @param category 类别名称 (如 "sponsor", "intro" 等)
     * @return 跳过模式，默认为 ALWAYS
     */
    public static SkipMode getSkipMode(String category) {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return SkipMode.ALWAYS;

        String key = "skip_mode_" + category;
        int value = prefs.getInt(key, SkipMode.ALWAYS.getValue());
        return SkipMode.fromValue(value);
    }

    /**
     * 设置指定类别的跳过模式
     */
    public static void setSkipMode(String category, SkipMode mode) {
        if (sharedPreferences != null) {
            String key = "skip_mode_" + category;
            sharedPreferences.edit().putInt(key, mode.getValue()).apply();
        }
    }

    /**
     * 获取赞助商广告的跳过模式
     */
    public static SkipMode getSponsorSkipMode() {
        return getSkipMode("sponsor");
    }

    /**
     * 获取自我推广的跳过模式
     */
    public static SkipMode getSelfPromoSkipMode() {
        return getSkipMode("selfpromo");
    }

    /**
     * 获取片头的跳过模式
     */
    public static SkipMode getIntroSkipMode() {
        return getSkipMode("intro");
    }

    /**
     * 获取片尾的跳过模式
     */
    public static SkipMode getOutroSkipMode() {
        return getSkipMode("outro");
    }

    /**
     * 获取互动提醒的跳过模式
     */
    public static SkipMode getInteractionSkipMode() {
        return getSkipMode("interaction");
    }

    /**
     * 获取预览/回顾的跳过模式
     */
    public static SkipMode getPreviewSkipMode() {
        return getSkipMode("preview");
    }

    /**
     * 获取填充内容的跳过模式
     */
    public static SkipMode getFillerSkipMode() {
        return getSkipMode("filler");
    }

    /**
     * 获取非音乐部分的跳过模式
     */
    public static SkipMode getMusicOfftopicSkipMode() {
        return getSkipMode("music_offtopic");
    }

    // ========== 颜色设置 ==========

    // 默认颜色
    public static final int DEFAULT_COLOR_SPONSOR = 0xFFFF0000;      // 红色
    public static final int DEFAULT_COLOR_SELF_PROMO = 0xFFFFA500;   // 橙色
    public static final int DEFAULT_COLOR_INTRO = 0xFF00FF00;        // 绿色
    public static final int DEFAULT_COLOR_OUTRO = 0xFF0000FF;        // 蓝色
    public static final int DEFAULT_COLOR_INTERACTION = 0xFFFF00FF;  // 紫色
    public static final int DEFAULT_COLOR_PREVIEW = 0xFF00FFFF;      // 青色
    public static final int DEFAULT_COLOR_FILLER = 0xFFFFFF00;       // 黄色
    public static final int DEFAULT_COLOR_MUSIC = 0xFF808080;        // 灰色

    /**
     * 获取指定类别的颜色
     */
    public static int getCategoryColor(String category) {
        SharedPreferences prefs = getPrefs();
        if (prefs == null) return getDefaultColor(category);

        String key = "color_" + category;
        return prefs.getInt(key, getDefaultColor(category));
    }

    /**
     * 设置指定类别的颜色
     */
    public static void setCategoryColor(String category, int color) {
        if (sharedPreferences != null) {
            String key = "color_" + category;
            sharedPreferences.edit().putInt(key, color).apply();
        }
    }

    /**
     * 获取默认颜色
     */
    private static int getDefaultColor(String category) {
        switch (category) {
            case "sponsor": return DEFAULT_COLOR_SPONSOR;
            case "selfpromo": return DEFAULT_COLOR_SELF_PROMO;
            case "intro": return DEFAULT_COLOR_INTRO;
            case "outro": return DEFAULT_COLOR_OUTRO;
            case "interaction": return DEFAULT_COLOR_INTERACTION;
            case "preview": return DEFAULT_COLOR_PREVIEW;
            case "filler": return DEFAULT_COLOR_FILLER;
            case "music_offtopic": return DEFAULT_COLOR_MUSIC;
            default: return DEFAULT_COLOR_SPONSOR;
        }
    }

    /**
     * 设置模块启用状态
     */
    public static void setModuleEnabled(boolean enabled) {
        if (sharedPreferences != null) {
            sharedPreferences.edit().putBoolean(KEY_MODULE_ENABLED, enabled).apply();
        }
    }

    /**
     * 设置 API 服务器
     */
    public static void setApiServer(String server) {
        if (sharedPreferences != null) {
            sharedPreferences.edit().putString(KEY_API_SERVER, server).apply();
        }
    }

    // ========== 测试模式 ==========

    /**
     * 是否启用测试模式
     */
    public static boolean isTestMode() {
        SharedPreferences prefs = getPrefs();
        return prefs != null && prefs.getBoolean(KEY_TEST_MODE, false);
    }

    /**
     * 设置测试模式
     */
    public static void setTestMode(boolean enabled) {
        if (sharedPreferences != null) {
            sharedPreferences.edit().putBoolean(KEY_TEST_MODE, enabled).apply();
        }
    }
}
