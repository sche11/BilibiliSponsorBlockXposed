package com.example.bilibilisponsorblock;

import android.content.Context;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 模块入口
 * 参考 PC 版的初始化流程
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static String modulePath;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 只Hook Bilibili主应用和概念版
        if (!lpparam.packageName.equals("tv.danmaku.bili") &&
            !lpparam.packageName.equals("com.bilibili.app.in")) {
            return;
        }

        // 初始化偏好设置（Xposed 环境）
        Preferences.initXposed();

        // 延迟初始化，等待应用完全启动
        try {
            Context context = (Context) lpparam.classLoader
                .loadClass("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null);

            if (context != null) {
                Preferences.init(context);
                LogUtils.init(context);
                LogUtils.getInstance().log("MainHook", "上下文初始化完成");
            }
        } catch (Exception e) {
            // 初始化失败但不影响功能
        }

        // 初始化播放器Hook
        PlayerHook.init(lpparam);

        // 初始化进度条Hook
        ProgressBarHook.init(lpparam);

        LogUtils.getInstance().log("MainHook", "模块加载成功 - " + lpparam.packageName);
    }

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    public static String getModulePath() {
        return modulePath;
    }
}
