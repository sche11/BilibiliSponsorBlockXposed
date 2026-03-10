package com.example.bilibilisponsorblock;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.Switch;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Material You 风格的设置界面
 * 使用动态莫奈取色，现代化的卡片式设计
 */
public class MaterialSettingsActivity extends Activity {

    private static final String TAG = "MaterialSettings";
    
    private int monetPrimary;
    private int monetSurface;
    private int monetSurfaceVariant;
    private int monetSecondary;
    
    private SharedPreferences prefs;
    private LinearLayout contentLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        prefs = getSharedPreferences(Preferences.PREFS_NAME, MODE_PRIVATE);
        LogUtils.init(this);
        
        initMonetColors();
        applyTheme();
        createUI();
        
        if (getActionBar() != null) {
            getActionBar().hide();
        }
    }
    
    private void initMonetColors() {
        monetPrimary = MonetColorUtils.getMonetPrimaryColor(this);
        monetSurface = MonetColorUtils.getMonetSurfaceColor(this);
        monetSurfaceVariant = MonetColorUtils.getMonetBackgroundColor(this);
        monetSecondary = MonetColorUtils.getMonetSecondaryColor(this);
    }
    
    private void applyTheme() {
        getWindow().setStatusBarColor(monetSurface);
        getWindow().setNavigationBarColor(monetSurface);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }
    
    private void createUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(monetSurface);
        
        contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(24));
        
        createHeader();
        createMainSwitchSection();
        createFunctionSection();
        createSkipCategorySection();
        createAboutSection();
        
        scrollView.addView(contentLayout);
        setContentView(scrollView);
    }
    
    private void createHeader() {
        TextView titleView = new TextView(this);
        titleView.setText("空降助手");
        titleView.setTextSize(32);
        titleView.setTextColor(monetPrimary);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(dpToPx(16), dpToPx(16), 0, dpToPx(4));
        contentLayout.addView(titleView);
        
        TextView subtitleView = new TextView(this);
        subtitleView.setText("Bilibili SponsorBlock");
        subtitleView.setTextSize(14);
        subtitleView.setTextColor(monetSecondary);
        subtitleView.setPadding(dpToPx(16), 0, 0, dpToPx(24));
        contentLayout.addView(subtitleView);
    }
    
    private void createMainSwitchSection() {
        addSectionTitle("主开关");
        
        LinearLayout card = createCard();
        card.addView(createSwitchRow("启用空降助手", "开启 SponsorBlock 功能",
            Preferences.KEY_MODULE_ENABLED, true));
        card.addView(createDivider());
        card.addView(createSwitchRow("显示跳过提示", "跳过片段时显示提示",
            Preferences.KEY_SHOW_TOAST, true));
        
        contentLayout.addView(card);
    }
    
    private void createFunctionSection() {
        addSectionTitle("功能");

        LinearLayout card = createCard();
        card.addView(createClickableRow("提交空降片段", "标记并提交新的空降片段",
            v -> startActivity(new Intent(this, SubmitSegmentActivity.class))));
        card.addView(createDivider());
        card.addView(createClickableRow("片段颜色设置", "自定义进度条片段颜色",
            v -> startActivity(new Intent(this, ColorSettingsActivity.class))));

        contentLayout.addView(card);
    }
    
    private void createSkipCategorySection() {
        addSectionTitle("跳过类别");

        LinearLayout card = createCard();

        String[][] categories = {
            {"sponsor", "赞助商广告", "赞助商广告片段"},
            {"selfpromo", "自我推广", "UP 主的自我推广"},
            {"intro", "片头", "片头动画/介绍"},
            {"outro", "片尾", "片尾/结束画面"},
            {"interaction", "互动提醒", "点赞、订阅等互动提醒"},
            {"preview", "预览/回顾", "内容预览或回顾"},
            {"filler", "填充内容", "与视频主题无关的填充内容"},
            {"music_offtopic", "非音乐部分", "音乐视频中的非音乐片段"},
        };

        for (int i = 0; i < categories.length; i++) {
            String[] cat = categories[i];
            card.addView(createSkipModeRow(cat[0], cat[1], cat[2]));
            if (i < categories.length - 1) {
                card.addView(createDivider());
            }
        }

        contentLayout.addView(card);
    }

    /**
     * 创建跳过模式选择行
     */
    private LinearLayout createSkipModeRow(String category, String title, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // 左侧文字区域
        LinearLayout textLayout = new LinearLayout(this);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        textLayout.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTextColor(monetSecondary);
        textLayout.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText(description);
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(monetSecondary);
        textLayout.addView(tvDesc);

        row.addView(textLayout);

        // 右侧模式选择按钮
        SkipMode currentMode = Preferences.getSkipMode(category);
        Button modeButton = new Button(this);
        modeButton.setText(currentMode.getDisplayName());
        modeButton.setTextSize(12);
        modeButton.setTextColor(monetPrimary);
        modeButton.setBackgroundColor(android.graphics.Color.TRANSPARENT);

        // 点击切换模式
        modeButton.setOnClickListener(v -> {
            showSkipModeDialog(category, title, modeButton);
        });

        row.addView(modeButton);

        return row;
    }

    /**
     * 显示跳过模式选择对话框
     */
    private void showSkipModeDialog(String category, String title, Button modeButton) {
        SkipMode currentMode = Preferences.getSkipMode(category);
        String[] modeNames = SkipMode.getDisplayNames();

        new android.app.AlertDialog.Builder(this)
            .setTitle(title + " - 跳过模式")
            .setSingleChoiceItems(modeNames, currentMode.getValue(), (dialog, which) -> {
                SkipMode selectedMode = SkipMode.fromValue(which);
                Preferences.setSkipMode(category, selectedMode);
                modeButton.setText(selectedMode.getDisplayName());
                dialog.dismiss();
            })
            .setNegativeButton("取消", null)
            .show();
    }
    
    private void createAboutSection() {
        addSectionTitle("关于");
        
        LinearLayout card = createCard();
        
        TextView aboutText = new TextView(this);
        aboutText.setText("空降助手 (Bilibili SponsorBlock)\n版本 1.0\n\n基于 BiliPai 和 SponsorBlock 官方实现\nAPI: https://bsbsb.top/api");
        aboutText.setTextSize(14);
        aboutText.setTextColor(monetSecondary);
        aboutText.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        card.addView(aboutText);
        
        contentLayout.addView(card);
    }
    
    private void addSectionTitle(String title) {
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(14);
        titleView.setTextColor(monetPrimary);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(8));
        contentLayout.addView(titleView);
    }
    
    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createCardBackground());
        card.setElevation(dpToPx(2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dpToPx(8));
        card.setLayoutParams(params);
        return card;
    }
    
    private View createSwitchRow(String title, String subtitle, String key, boolean defaultValue) {
        // 创建主容器
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        container.setClickable(true);
        container.setFocusable(true);
        
        // 文字容器
        LinearLayout textContainer = new LinearLayout(this);
        textContainer.setOrientation(LinearLayout.VERTICAL);
        textContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF1C1B1F);
        textContainer.addView(titleView);
        
        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(12);
        subtitleView.setTextColor(monetSecondary);
        textContainer.addView(subtitleView);
        
        container.addView(textContainer);
        
        // 创建药丸状 Switch（使用系统默认样式）
        Switch switchView = new Switch(this);
        switchView.setChecked(prefs.getBoolean(key, defaultValue));
        
        // 禁用 Switch 的默认点击，由容器处理
        switchView.setClickable(false);
        switchView.setFocusable(false);
        
        container.addView(switchView);
        
        // 容器点击事件 - 切换 Switch
        container.setOnClickListener(v -> {
            boolean newState = !switchView.isChecked();
            switchView.setChecked(newState);
            prefs.edit().putBoolean(key, newState).apply();
        });
        
        return container;
    }
    
    private View createClickableRow(String title, String subtitle, View.OnClickListener listener) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16));
        container.setClickable(true);
        container.setFocusable(true);
        container.setForeground(getRippleDrawable());
        container.setOnClickListener(listener);
        
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(16);
        titleView.setTextColor(0xFF1C1B1F);
        container.addView(titleView);
        
        TextView subtitleView = new TextView(this);
        subtitleView.setText(subtitle);
        subtitleView.setTextSize(12);
        subtitleView.setTextColor(monetSecondary);
        container.addView(subtitleView);
        
        return container;
    }
    
    private View createDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(monetSurfaceVariant);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(dpToPx(16), 0, dpToPx(16), 0);
        divider.setLayoutParams(params);
        return divider;
    }
    
    private GradientDrawable createCardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(monetSurfaceVariant);
        drawable.setCornerRadius(dpToPx(16));
        return drawable;
    }
    
    private android.graphics.drawable.Drawable getRippleDrawable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(monetPrimary & 0x20FFFFFF),
                null, null);
        }
        return null;
    }
    
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
