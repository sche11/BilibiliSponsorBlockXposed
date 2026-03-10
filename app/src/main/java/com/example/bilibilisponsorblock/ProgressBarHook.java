package com.example.bilibilisponsorblock;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.List;

/**
 * 进度条 Hook 类
 * 在播放器进度条上绘制片段颜色标记
 */
public class ProgressBarHook {

    private static final String TAG = "ProgressBarHook";

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            LogUtils.getInstance().log(TAG, "开始初始化 ProgressBarHook");

            // Hook AbsSeekBar 的 onDraw 方法
            hookSeekBar(lpparam);

            // Hook ProgressBar
            hookProgressBar(lpparam);

            // Hook Bilibili 自定义进度条
            hookBilibiliSeekBar(lpparam);

            LogUtils.getInstance().log(TAG, "ProgressBarHook 初始化完成");
        } catch (Exception e) {
            LogUtils.getInstance().logError(TAG, "初始化失败", e);
        }
    }

    private static void hookSeekBar(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 尝试 Hook AbsSeekBar
            Class<?> absSeekBarClass = XposedHelpers.findClass(
                "android.widget.AbsSeekBar", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(absSeekBarClass, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Canvas canvas = (Canvas) param.args[0];
                            View seekBar = (View) param.thisObject;

                            String className = seekBar.getClass().getName();
                            // 只处理 Bilibili 相关的进度条
                            if (className.contains("bili") || className.contains("player") ||
                                className.contains("SeekBar") || className.contains("Progress")) {
                                LogUtils.getInstance().logDebug(TAG, "AbsSeekBar onDraw: " + className);
                                drawSegmentMarkers(canvas, seekBar);
                            }
                        } catch (Exception e) {
                            LogUtils.getInstance().logDebug(TAG, "绘制标记失败: " + e.getMessage());
                        }
                    }
                });

            LogUtils.getInstance().log(TAG, "AbsSeekBar Hook 成功");
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "AbsSeekBar Hook 失败: " + e.getMessage());
        }
    }

    private static void hookProgressBar(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> progressBarClass = XposedHelpers.findClass(
                "android.widget.ProgressBar", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(progressBarClass, "onDraw", Canvas.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Canvas canvas = (Canvas) param.args[0];
                            View progressBar = (View) param.thisObject;

                            String className = progressBar.getClass().getName();
                            if (className.contains("bili") || className.contains("player") ||
                                className.contains("video") || className.contains("Video")) {
                                LogUtils.getInstance().logDebug(TAG, "ProgressBar onDraw: " + className);
                                drawSegmentMarkers(canvas, progressBar);
                            }
                        } catch (Exception e) {
                            LogUtils.getInstance().logDebug(TAG, "ProgressBar 绘制失败: " + e.getMessage());
                        }
                    }
                });

            LogUtils.getInstance().log(TAG, "ProgressBar Hook 成功");
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "ProgressBar Hook 失败: " + e.getMessage());
        }
    }

    private static void hookBilibiliSeekBar(XC_LoadPackage.LoadPackageParam lpparam) {
        // Bilibili 可能使用自定义的 SeekBar
        // 根据日志，实际使用的是 com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3
        String[] possibleClasses = {
            "com.bilibili.playerbizcommonv2.widget.seek.v3.PlayerSeekWidget3",
            "com.bilibili.playerbizcommonv2.widget.seek.PlayerSeekWidget",
            "com.bilibili.playerbizcommonv2.widget.seek.v2.PlayerSeekWidget2",
            "com.bilibili.playerbizcommonv2.widget.seek.SeekWidget",
            "tv.danmaku.bili.ui.video.player.view.VideoSeekBar",
            "tv.danmaku.bili.player.view.PlayerSeekBar",
            "com.bilibili.player.view.SeekBar",
            "tv.danmaku.bili.videoplayer.view.VideoSeekBar",
            "tv.danmaku.bili.widget.SeekBar",
            "tv.danmaku.bili.player.widget.VideoProgressBar",
            "tv.danmaku.bili.player.widget.PlayerSeekBar",
            "tv.danmaku.bili.player.widget.BiliPlayerSeekBar",
            "tv.danmaku.bili.ui.video.player.widget.VideoPlayerSeekBar"
        };

        for (String className : possibleClasses) {
            try {
                Class<?> clazz = XposedHelpers.findClass(className, lpparam.classLoader);

                // 尝试 Hook onDraw
                try {
                    XposedHelpers.findAndHookMethod(clazz, "onDraw", Canvas.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Canvas canvas = (Canvas) param.args[0];
                                    View seekBar = (View) param.thisObject;
                                    LogUtils.getInstance().logDebug(TAG, "Bilibili SeekBar onDraw: " + className);
                                    drawSegmentMarkers(canvas, seekBar);
                                } catch (Exception e) {
                                    LogUtils.getInstance().logDebug(TAG, "Bilibili SeekBar 绘制失败: " + e.getMessage());
                                }
                            }
                        });
                    LogUtils.getInstance().log(TAG, "Hook onDraw 成功: " + className);
                } catch (Exception e1) {
                    // onDraw 可能不存在，尝试 Hook dispatchDraw
                    try {
                        XposedHelpers.findAndHookMethod(clazz, "dispatchDraw", Canvas.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        Canvas canvas = (Canvas) param.args[0];
                                        View seekBar = (View) param.thisObject;
                                        LogUtils.getInstance().logDebug(TAG, "Bilibili SeekBar dispatchDraw: " + className);
                                        drawSegmentMarkers(canvas, seekBar);
                                    } catch (Exception e) {
                                        LogUtils.getInstance().logDebug(TAG, "Bilibili SeekBar dispatchDraw 绘制失败: " + e.getMessage());
                                    }
                                }
                            });
                        LogUtils.getInstance().log(TAG, "Hook dispatchDraw 成功: " + className);
                    } catch (Exception e2) {
                        LogUtils.getInstance().logDebug(TAG, "Hook " + className + " 失败: " + e2.getMessage());
                        continue;
                    }
                }
                return;
            } catch (Exception e) {
                // 继续尝试下一个
            }
        }

        LogUtils.getInstance().logDebug(TAG, "未找到可 Hook 的进度条类");
    }

    private static void drawSegmentMarkers(Canvas canvas, View seekBar) {
        // 获取当前视频的片段
        List<Segment> segments = PlayerHook.getCurrentSegments();

        if (segments == null || segments.isEmpty()) {
            LogUtils.getInstance().logDebug(TAG, "没有片段需要绘制");
            return;
        }

        // 获取视频总时长
        long durationMs = PlayerHook.getVideoDuration();

        if (durationMs <= 0) {
            LogUtils.getInstance().logDebug(TAG, "视频时长无效: " + durationMs);
            return;
        }

        LogUtils.getInstance().logDebug(TAG, "绘制片段标记: 片段数=" + segments.size() + ", 视频时长=" + durationMs + "ms");

        // 获取进度条尺寸
        int width = seekBar.getWidth();
        int height = seekBar.getHeight();
        int paddingLeft = seekBar.getPaddingLeft();
        int paddingRight = seekBar.getPaddingRight();

        int availableWidth = width - paddingLeft - paddingRight;
        if (availableWidth <= 0) return;

        // 绘制每个片段
        for (Segment segment : segments) {
            // 获取类别颜色
            int color = Preferences.getCategoryColor(segment.category);

            // 计算片段在进度条上的位置
            float startPercent = (float) (segment.segment[0] / (durationMs / 1000.0));
            float endPercent = (float) (segment.segment[1] / (durationMs / 1000.0));

            // 限制百分比在0-1范围内
            startPercent = Math.max(0, Math.min(1, startPercent));
            endPercent = Math.max(0, Math.min(1, endPercent));

            float startX = paddingLeft + startPercent * availableWidth;
            float endX = paddingLeft + endPercent * availableWidth;

            // 确保最小宽度
            if (endX - startX < 3) endX = startX + 3;

            // 绘制标记
            Paint paint = new Paint();
            paint.setColor(color);
            paint.setAlpha(200);
            paint.setAntiAlias(true);

            // 在进度条中间区域绘制彩色条，与进度条对齐
            // 再调细线条，使其更精致
            float barHeight = height * 0.12f;  // 从 0.20f 再调细到 0.12f
            float top = (height - barHeight) / 2f;  // 居中显示，移除偏移
            float bottom = top + barHeight;

            // 绘制圆角矩形，更加美观
            float cornerRadius = barHeight / 2f;  // 调整圆角
            RectF rect = new RectF(startX, top, endX, bottom);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);
            
            // 添加细边框效果
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.8f);  // 从 1.0f 再调细到 0.8f
            paint.setAlpha(140);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint);
        }
    }
}
