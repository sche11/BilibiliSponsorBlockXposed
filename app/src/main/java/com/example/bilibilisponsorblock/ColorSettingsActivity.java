package com.example.bilibilisponsorblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 片段颜色设置 Activity
 * 允许用户自定义进度条上不同片段类别的颜色
 * 使用莫奈取色 Material You 设计风格
 */
public class ColorSettingsActivity extends Activity {

    private static final String[] CATEGORIES = {
        "sponsor", "selfpromo", "intro", "outro",
        "interaction", "preview", "filler", "music_offtopic"
    };

    private static final String[] CATEGORY_NAMES = {
        "赞助商广告", "自我推广", "片头", "片尾",
        "互动提醒", "预览/回顾", "填充内容", "非音乐部分"
    };

    // 默认颜色
    private static final int[] DEFAULT_COLORS = {
        0xFFFF0000,  // 赞助商广告 - 红色
        0xFFFFA500,  // 自我推广 - 橙色
        0xFF00FF00,  // 片头 - 绿色
        0xFF0000FF,  // 片尾 - 蓝色
        0xFFFF00FF,  // 互动提醒 - 紫色
        0xFF00FFFF,  // 预览/回顾 - 青色
        0xFFFFFF00,  // 填充内容 - 黄色
        0xFF808080   // 非音乐部分 - 灰色
    };
    
    // 动态莫奈颜色
    private int monetPrimary;
    private int monetOnPrimary;
    private int monetSurface;
    private int monetOutline;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化日志
        LogUtils.init(this);
        
        // 初始化动态莫奈颜色
        initMonetColors();
        
        // 应用莫奈主题
        applyMonetTheme();

        // 创建UI
        createUI();

        // 设置返回按钮
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle("片段颜色设置");
            getActionBar().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(monetPrimary));
        }
    }
    
    private void initMonetColors() {
        monetPrimary = MonetColorUtils.getMonetPrimaryColor(this);
        monetOnPrimary = 0xFFFFFFFF;
        monetSurface = MonetColorUtils.getMonetSurfaceColor(this);
        monetOutline = MonetColorUtils.getMonetOutlineColor(this);
    }
    
    private void applyMonetTheme() {
        getWindow().setStatusBarColor(monetPrimary);
        getWindow().setBackgroundDrawable(
            new android.graphics.drawable.ColorDrawable(monetSurface));
    }

    private void createUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(monetSurface);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        layout.setBackgroundColor(monetSurface);

        // 标题 - 莫奈风格
        TextView tvTitle = new TextView(this);
        tvTitle.setText("自定义片段颜色");
        tvTitle.setTextSize(28);
        tvTitle.setTextColor(monetPrimary);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 8);
        layout.addView(tvTitle);

        // 说明
        TextView tvDesc = new TextView(this);
        tvDesc.setText("设置进度条上不同片段类别的显示颜色");
        tvDesc.setTextSize(14);
        tvDesc.setTextColor(monetOutline);
        tvDesc.setPadding(0, 0, 0, 32);
        layout.addView(tvDesc);

        // 为每个类别添加颜色选择
        for (int i = 0; i < CATEGORIES.length; i++) {
            addColorPicker(layout, CATEGORIES[i], CATEGORY_NAMES[i], DEFAULT_COLORS[i]);
        }

        // 重置按钮 - 莫奈风格
        Button btnReset = new Button(this);
        btnReset.setText("恢复默认颜色");
        btnReset.setTextColor(monetOnPrimary);
        btnReset.setBackground(createMonetFilledButtonBackground());
        btnReset.setAllCaps(false);
        btnReset.setElevation(4);
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resetParams.setMargins(0, 32, 0, 0);
        btnReset.setLayoutParams(resetParams);
        btnReset.setOnClickListener(v -> resetToDefault());
        layout.addView(btnReset);

        scrollView.addView(layout);
        setContentView(scrollView);
    }
    
    private android.graphics.drawable.Drawable createMonetFilledButtonBackground() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(monetPrimary);
        drawable.setCornerRadius(24);
        return drawable;
    }

    private void addColorPicker(LinearLayout parent, String category, String name, int defaultColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 16, 0, 16);

        // 颜色预览
        View colorPreview = new View(this);
        int currentColor = Preferences.getCategoryColor(category);
        colorPreview.setBackgroundColor(currentColor);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(80, 80);
        previewParams.setMargins(0, 0, 24, 0);
        colorPreview.setLayoutParams(previewParams);
        row.addView(colorPreview);

        // 类别名称
        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(16);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, 
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tvName);

        // 选择颜色按钮
        Button btnPick = new Button(this);
        btnPick.setText("选择");
        btnPick.setOnClickListener(v -> showColorPickerDialog(category, colorPreview));
        row.addView(btnPick);

        parent.addView(row);

        // 添加分隔线
        View divider = new View(this);
        divider.setBackgroundColor(0xFFCCCCCC);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));
        parent.addView(divider);
    }

    private void showColorPickerDialog(String category, View colorPreview) {
        int currentColor = Preferences.getCategoryColor(category);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择颜色 - " + getCategoryName(category));

        // 创建颜色选择器布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);

        // 颜色预览
        final View preview = new View(this);
        preview.setBackgroundColor(currentColor);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 120);
        previewParams.setMargins(0, 0, 0, 32);
        preview.setLayoutParams(previewParams);
        layout.addView(preview);

        // RGB滑块
        final int[] rgb = new int[]{Color.red(currentColor), Color.green(currentColor), Color.blue(currentColor)};

        // 红色
        layout.addView(createColorSlider("红色", rgb[0], value -> {
            rgb[0] = value;
            int newColor = Color.rgb(rgb[0], rgb[1], rgb[2]);
            preview.setBackgroundColor(newColor);
        }));

        // 绿色
        layout.addView(createColorSlider("绿色", rgb[1], value -> {
            rgb[1] = value;
            int newColor = Color.rgb(rgb[0], rgb[1], rgb[2]);
            preview.setBackgroundColor(newColor);
        }));

        // 蓝色
        layout.addView(createColorSlider("蓝色", rgb[2], value -> {
            rgb[2] = value;
            int newColor = Color.rgb(rgb[0], rgb[1], rgb[2]);
            preview.setBackgroundColor(newColor);
        }));

        // 预设颜色
        TextView tvPreset = new TextView(this);
        tvPreset.setText("预设颜色:");
        tvPreset.setPadding(0, 24, 0, 16);
        layout.addView(tvPreset);

        LinearLayout presetLayout = new LinearLayout(this);
        presetLayout.setOrientation(LinearLayout.HORIZONTAL);

        int[] presets = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFA500, 0xFF808080};
        for (int color : presets) {
            Button btn = new Button(this);
            btn.setBackgroundColor(color);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(80, 80);
            params.setMargins(8, 0, 8, 0);
            btn.setLayoutParams(params);
            btn.setOnClickListener(v -> {
                rgb[0] = Color.red(color);
                rgb[1] = Color.green(color);
                rgb[2] = Color.blue(color);
                preview.setBackgroundColor(color);
            });
            presetLayout.addView(btn);
        }
        layout.addView(presetLayout);

        builder.setView(layout);

        builder.setPositiveButton("确定", (dialog, which) -> {
            int newColor = Color.rgb(rgb[0], rgb[1], rgb[2]);
            Preferences.setCategoryColor(category, newColor);
            colorPreview.setBackgroundColor(newColor);
            LogUtils.getInstance().log("ColorSettings", "设置颜色: " + category + " = " + String.format("#%06X", newColor));
        });

        builder.setNegativeButton("取消", null);

        builder.show();
    }

    private LinearLayout createColorSlider(String label, int initialValue, OnColorChangeListener listener) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(0, 8, 0, 8);

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setWidth(80);
        layout.addView(tvLabel);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(255);
        seekBar.setProgress(initialValue);
        seekBar.setLayoutParams(new LinearLayout.LayoutParams(0, 
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView tvValue = new TextView(this);
        tvValue.setText(String.valueOf(initialValue));
        tvValue.setWidth(60);
        tvValue.setPadding(16, 0, 0, 0);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvValue.setText(String.valueOf(progress));
                listener.onColorChange(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        layout.addView(seekBar);
        layout.addView(tvValue);

        return layout;
    }

    private void resetToDefault() {
        new AlertDialog.Builder(this)
            .setTitle("恢复默认颜色")
            .setMessage("确定要恢复所有类别的默认颜色吗？")
            .setPositiveButton("确定", (dialog, which) -> {
                for (int i = 0; i < CATEGORIES.length; i++) {
                    Preferences.setCategoryColor(CATEGORIES[i], DEFAULT_COLORS[i]);
                }
                // 重新创建UI
                createUI();
                LogUtils.getInstance().log("ColorSettings", "已恢复默认颜色");
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private String getCategoryName(String category) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(category)) {
                return CATEGORY_NAMES[i];
            }
        }
        return category;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    interface OnColorChangeListener {
        void onColorChange(int value);
    }
}
