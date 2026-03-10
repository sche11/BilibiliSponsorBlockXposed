package com.example.bilibilisponsorblock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

public class SettingsActivity extends PreferenceActivity {

    private static final String TAG = "SettingsActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 应用动态莫奈主题（从系统壁纸取色）
        applyMonetTheme();
        
        try {
            // 加载偏好设置
            addPreferencesFromResource(R.xml.preferences);

            // 初始化日志系统
            LogUtils.init(this);

            // 设置日志查看点击事件
            Preference viewLogsPref = findPreference("view_logs");
            if (viewLogsPref != null) {
                viewLogsPref.setOnPreferenceClickListener(preference -> {
                    try {
                        Intent intent = new Intent(SettingsActivity.this, LogActivity.class);
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "打开日志界面失败", e);
                        Toast.makeText(this, "打开日志失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
            }

            // 设置提交片段点击事件
            Preference submitSegmentPref = findPreference("submit_segment");
            if (submitSegmentPref != null) {
                submitSegmentPref.setOnPreferenceClickListener(preference -> {
                    try {
                        Intent intent = new Intent(SettingsActivity.this, SubmitSegmentActivity.class);
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "打开提交片段界面失败", e);
                        Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
            }

            // 设置颜色设置点击事件
            Preference colorSettingsPref = findPreference("color_settings");
            if (colorSettingsPref != null) {
                colorSettingsPref.setOnPreferenceClickListener(preference -> {
                    try {
                        Intent intent = new Intent(SettingsActivity.this, ColorSettingsActivity.class);
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "打开颜色设置界面失败", e);
                        Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
            }

            // 记录启动日志
            LogUtils.getInstance().log("Settings", "设置界面已打开");
            
        } catch (Exception e) {
            Log.e(TAG, "onCreate 失败", e);
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            // 更新日志查看的摘要信息
            Preference viewLogsPref = findPreference("view_logs");
            if (viewLogsPref != null) {
                String size = LogUtils.getInstance().getFormattedLogSize();
                viewLogsPref.setSummary("日志大小: " + size + "，点击查看详情");
            }
        } catch (Exception e) {
            Log.e(TAG, "onResume 失败", e);
        }
    }
    
    /**
     * 应用莫奈主题样式（动态从系统壁纸取色）
     */
    private void applyMonetTheme() {
        try {
            // 从系统壁纸获取动态颜色
            int primaryColor = MonetColorUtils.getMonetPrimaryColor(this);
            int surfaceColor = MonetColorUtils.getMonetSurfaceColor(this);
            
            // 设置 ActionBar 颜色
            if (getActionBar() != null) {
                getActionBar().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(primaryColor));
                getActionBar().setTitle("空降助手设置");
            }
            
            // 设置窗口背景
            getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(surfaceColor));
            
            // 设置状态栏颜色
            getWindow().setStatusBarColor(primaryColor);
            
        } catch (Exception e) {
            Log.e(TAG, "应用主题失败", e);
        }
    }
}
