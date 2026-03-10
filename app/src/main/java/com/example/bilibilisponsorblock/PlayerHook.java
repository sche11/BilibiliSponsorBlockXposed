package com.example.bilibilisponsorblock;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/**
 * 播放器 Hook 类
 * 参考 BiliPai、PiliPlus 和 BilibiliSponsorBlock 官方实现
 * 
 * 核心策略：
 * 1. Hook Bilibili的网络请求，拦截视频详情API获取BV号和CID
 * 2. Hook播放器相关类获取播放状态
 * 3. 定时检查并跳过片段
 */

/**
 * BV号和AV号转换工具
 * 参考：https://github.com/Goooler/bilibili-API-collect/blob/master/docs/misc/bvid_desc.md
 * 使用新的算法：XOR_CODE = 23442827791579, MASK_CODE = 2251799813685247
 */
class BvidConverter {
    private static final java.math.BigInteger XOR_CODE = java.math.BigInteger.valueOf(23442827791579L);
    private static final java.math.BigInteger MASK_CODE = java.math.BigInteger.valueOf(2251799813685247L);
    private static final java.math.BigInteger MAX_AID = java.math.BigInteger.ONE.shiftLeft(51);
    private static final int BASE = 58;
    private static final String DATA = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";
    
    public static String aidToBvid(long aid) {
        java.math.BigInteger aidBig = java.math.BigInteger.valueOf(aid);
        char[] bytes = {'B', 'V', '1', '0', '0', '0', '0', '0', '0', '0', '0', '0'};
        int bvIndex = bytes.length - 1;
        java.math.BigInteger tmp = MAX_AID.or(aidBig).xor(XOR_CODE);
        java.math.BigInteger baseBig = java.math.BigInteger.valueOf(BASE);
        
        while (tmp.compareTo(java.math.BigInteger.ZERO) > 0) {
            bytes[bvIndex] = DATA.charAt(tmp.mod(baseBig).intValue());
            tmp = tmp.divide(baseBig);
            bvIndex--;
        }
        
        // 交换位置
        swap(bytes, 3, 9);
        swap(bytes, 4, 7);
        
        return new String(bytes);
    }
    
    public static long bvidToAid(String bvid) {
        if (bvid == null || bvid.length() < 12) return 0;
        char[] bvidArr = bvid.toCharArray();
        
        // 交换位置
        swap(bvidArr, 3, 9);
        swap(bvidArr, 4, 7);
        
        String adjustedBvid = new String(bvidArr, 3, bvidArr.length - 3);
        java.math.BigInteger tmp = java.math.BigInteger.ZERO;
        java.math.BigInteger baseBig = java.math.BigInteger.valueOf(BASE);
        
        for (char c : adjustedBvid.toCharArray()) {
            tmp = tmp.multiply(baseBig).add(java.math.BigInteger.valueOf(DATA.indexOf(c)));
        }
        
        java.math.BigInteger result = tmp.and(MASK_CODE).xor(XOR_CODE);
        return result.longValue();
    }
    
    private static void swap(char[] array, int i, int j) {
        char temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }
}
public class PlayerHook {

    private static final long CHECK_INTERVAL = 200;
    private static final long PRE_SKIP_THRESHOLD = 300;
    private static final double MIN_SEGMENT_DURATION = 0.5;
    private static final String TAG = "PlayerHook";

    private static final class PlayerState {
        Object player;
        VideoInfo videoInfo = new VideoInfo();
        List<Segment> segments = new CopyOnWriteArrayList<>();
        final Set<String> skippedSegments = ConcurrentHashMap.newKeySet();
        volatile boolean isActive = false;
        volatile boolean isPaused = false;
        volatile long currentPosition = 0;

        PlayerState(Object player) {
            this.player = player;
        }

        void reset() {
            videoInfo.reset();
            segments.clear();
            skippedSegments.clear();
            isActive = false;
            isPaused = false;
            currentPosition = 0;
        }
    }

    private static final WeakHashMap<Object, PlayerState> playerStates = new WeakHashMap<>();
    private static final AtomicReference<Object> activePlayer = new AtomicReference<>();
    private static final AtomicReference<PlayerState> activeState = new AtomicReference<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> checkTask;
    private static final AtomicLong lastSkipTime = new AtomicLong(0);
    private static final long SKIP_COOLDOWN = 1000;

    // 存储最后已知的视频信息
    private static final VideoInfo lastKnownVideoInfo = new VideoInfo();

    // 当前视频信息（从网络请求拦截获取）
    private static final AtomicReference<VideoInfo> currentVideoInfo = new AtomicReference<>();

    // 当前视频的片段（用于跨组件共享）
    private static final List<Segment> currentSegments = new CopyOnWriteArrayList<>();
    
    // 防止重复显示 Toast
    private static String lastToastVideoKey = "";

    // 提交按钮映射
    private static final WeakHashMap<Activity, android.view.View> submitButtons = new WeakHashMap<>();

    // 用于关联请求 URL 和响应
    private static final ThreadLocal<String> currentRequestUrl = new ThreadLocal<>();

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            LogUtils.getInstance().log(TAG, "开始初始化 PlayerHook");

            // 1. Hook网络请求 - 这是获取视频信息的关键
            hookNetworkRequests(lpparam);

            // 2. Hook播放器
            hookPlayerClasses(lpparam);
            
            // 3. Hook视频相关Activity
            hookVideoActivities(lpparam);
            
            // 4. 启动定时检查
            startPeriodicCheck();
            
            LogUtils.getInstance().log(TAG, "PlayerHook 初始化完成");
        } catch (Exception e) {
            LogUtils.getInstance().logError(TAG, "初始化失败", e);
        }
    }

    /**
     * Hook网络请求 - 核心方法
     * 拦截Bilibili的视频详情API获取BV号和CID
     */
    private static void hookNetworkRequests(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook OkHttp (Bilibili主要使用的网络库)
            hookOkHttp(lpparam);
            
            // Hook HttpURLConnection (备用)
            hookHttpURLConnection(lpparam);
            
            LogUtils.getInstance().log(TAG, "网络请求 Hook 完成");
        } catch (Exception e) {
            LogUtils.getInstance().logError(TAG, "网络请求 Hook 失败", e);
        }
    }

    /**
     * Hook OkHttp - 通过Hook ResponseBody来拦截所有响应
     */
    private static void hookOkHttp(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            hookOkHttpResponseBody(lpparam);
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "OkHttp ResponseBody Hook 失败: " + e.getMessage());
        }

        // 备用方案：Hook Call.execute()
        try {
            hookCallExecute(lpparam);
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "Call.execute Hook 失败: " + e.getMessage());
        }

        // Hook OkHttpClient.newCall 来跟踪请求 URL
        try {
            hookOkHttpClientNewCall(lpparam);
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "OkHttpClient.newCall Hook 失败: " + e.getMessage());
        }
    }

    /**
     * Hook OkHttpClient.newCall 来跟踪请求 URL
     */
    private static void hookOkHttpClientNewCall(XC_LoadPackage.LoadPackageParam lpparam) {
        // 方法1：Hook OkHttpClient.newCall
        try {
            Class<?> clientClass = XposedHelpers.findClass("okhttp3.OkHttpClient", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(clientClass, "newCall",
                XposedHelpers.findClass("okhttp3.Request", lpparam.classLoader),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object request = param.args[0];
                            Object url = XposedHelpers.callMethod(request, "url");
                            String urlString = url.toString();

                            // 存储请求 URL
                            currentRequestUrl.set(urlString);

                            if (shouldProcessUrl(urlString)) {
                                LogUtils.getInstance().logDebug(TAG, "newCall URL: " + urlString);
                            }
                        } catch (Exception e) {
                            LogUtils.getInstance().logDebug(TAG, "newCall 处理失败: " + e.getMessage());
                        }
                    }
                });

            LogUtils.getInstance().log(TAG, "OkHttpClient.newCall Hook 成功");
            return;
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "OkHttpClient.newCall Hook 失败: " + e.getMessage());
        }

        // 方法2：Hook Request.Builder.build
        try {
            Class<?> builderClass = XposedHelpers.findClass("okhttp3.Request$Builder", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(builderClass, "build",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object request = param.getResult();
                            Object url = XposedHelpers.callMethod(request, "url");
                            String urlString = url.toString();

                            // 存储请求 URL
                            currentRequestUrl.set(urlString);

                            if (shouldProcessUrl(urlString)) {
                                LogUtils.getInstance().log(TAG, "Request.Builder.build URL: " + urlString);
                            }
                        } catch (Exception e) {
                            LogUtils.getInstance().logDebug(TAG, "Request.Builder.build 处理失败: " + e.getMessage());
                        }
                    }
                });

            LogUtils.getInstance().log(TAG, "Request.Builder.build Hook 成功");
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "Request.Builder.build Hook 失败: " + e.getMessage());
        }
    }

    /**
     * Hook Call.execute() 作为备用方案
     */
    private static void hookCallExecute(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 尝试 Hook RealCall 而不是抽象的 Call 接口
            String[] callImplClasses = {
                "okhttp3.RealCall",
                "okhttp3.internal.connection.RealCall",
                "okhttp3.RealCall$1"
            };

            for (String className : callImplClasses) {
                try {
                    Class<?> callClass = XposedHelpers.findClass(className, lpparam.classLoader);

                    XposedHelpers.findAndHookMethod(callClass, "execute",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Object response = param.getResult();
                                    if (response == null) return;

                                    Object request = XposedHelpers.callMethod(response, "request");
                                    Object url = XposedHelpers.callMethod(request, "url");
                                    String urlString = url.toString();

                                    if (shouldProcessUrl(urlString)) {
                                        LogUtils.getInstance().log(TAG, "RealCall.execute()拦截: " + urlString);

                                        // 获取响应体
                                        Object body = XposedHelpers.callMethod(response, "body");
                                        if (body != null) {
                                            try {
                                                String responseBody = (String) XposedHelpers.callMethod(body, "string");
                                                if (responseBody != null && !responseBody.isEmpty()) {
                                                    parseVideoInfoFromResponse(urlString, responseBody);
                                                }
                                            } catch (Exception e) {
                                                LogUtils.getInstance().logDebug(TAG, "RealCall.execute() 读取响应体失败: " + e.getMessage());
                                            }
                                        }
                                    }
                                } catch (Exception e) {
                                    LogUtils.getInstance().logDebug(TAG, "RealCall.execute() 处理失败: " + e.getMessage());
                                }
                            }
                        });

                    LogUtils.getInstance().log(TAG, "RealCall.execute Hook 成功: " + className);
                    return;
                } catch (Throwable e) {
                    LogUtils.getInstance().logDebug(TAG, className + " Hook 失败: " + e.getMessage());
                }
            }

            LogUtils.getInstance().logDebug(TAG, "所有 Call 实现类 Hook 失败");
        } catch (Throwable e) {
            LogUtils.getInstance().logDebug(TAG, "hookCallExecute 失败: " + e.getMessage());
        }
    }

    private static void hookOkHttpResponseBody(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> bodyClass = XposedHelpers.findClass("okhttp3.ResponseBody", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(bodyClass, "string",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String responseBody = (String) param.getResult();

                            if (responseBody == null || responseBody.isEmpty()) return;

                            LogUtils.getInstance().logDebug(TAG, "ResponseBody.string() 被调用，响应长度: " + responseBody.length());

                            // 打印响应内容的前200个字符用于调试
                            String preview = responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody;
                            LogUtils.getInstance().logDebug(TAG, "响应内容预览: " + preview);

                            // 方法1：使用 ThreadLocal 中存储的 URL
                            String urlString = currentRequestUrl.get();
                            LogUtils.getInstance().logDebug(TAG, "ThreadLocal URL: " + urlString);

                            // 方法2：尝试从 ResponseBody 获取
                            if (urlString == null) {
                                Object body = param.thisObject;
                                Object response = getResponseFromBody(body);
                                if (response != null) {
                                    Object request = XposedHelpers.callMethod(response, "request");
                                    Object url = XposedHelpers.callMethod(request, "url");
                                    urlString = url.toString();
                                    LogUtils.getInstance().logDebug(TAG, "从 ResponseBody 获取 URL: " + urlString);
                                }
                            }

                            // 方法3：直接检查响应内容是否包含视频信息
                            if (urlString == null) {
                                urlString = extractUrlFromResponse(responseBody);
                                LogUtils.getInstance().logDebug(TAG, "从响应内容提取 URL: " + urlString);
                            }

                            if (urlString == null) {
                                LogUtils.getInstance().logDebug(TAG, "无法获取 URL，跳过处理");
                                return;
                            }

                            LogUtils.getInstance().logDebug(TAG, "检查 URL: " + urlString + ", shouldProcessUrl: " + shouldProcessUrl(urlString));

                            if (shouldProcessUrl(urlString)) {
                                LogUtils.getInstance().log(TAG, "ResponseBody.string()拦截: " + urlString);
                                // 先从 URL 提取 CID 和 BV
                                extractCidFromPlayUrl(urlString);
                                // 再解析响应内容
                                parseVideoInfoFromResponse(urlString, responseBody);
                            }
                        } catch (Exception e) {
                            LogUtils.getInstance().logError(TAG, "处理ResponseBody.string失败", e);
                        }
                    }
                });

            XposedHelpers.findAndHookMethod(bodyClass, "bytes",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            byte[] bytes = (byte[]) param.getResult();

                            if (bytes == null || bytes.length == 0) return;

                            // 方法1：使用 ThreadLocal 中存储的 URL
                            String urlString = currentRequestUrl.get();

                            // 方法2：尝试从 ResponseBody 获取
                            if (urlString == null) {
                                Object body = param.thisObject;
                                Object response = getResponseFromBody(body);
                                if (response != null) {
                                    Object request = XposedHelpers.callMethod(response, "request");
                                    Object url = XposedHelpers.callMethod(request, "url");
                                    urlString = url.toString();
                                }
                            }

                            // 方法3：直接检查响应内容是否包含视频信息
                            if (urlString == null) {
                                String responseBody = new String(bytes, StandardCharsets.UTF_8);
                                urlString = extractUrlFromResponse(responseBody);
                            }

                            if (urlString == null) return;

                            if (shouldProcessUrl(urlString)) {
                                LogUtils.getInstance().log(TAG, "ResponseBody.bytes()拦截: " + urlString);
                                // 先从 URL 提取 CID 和 BV
                                extractCidFromPlayUrl(urlString);
                                // 再解析响应内容
                                String responseBody = new String(bytes, StandardCharsets.UTF_8);
                                parseVideoInfoFromResponse(urlString, responseBody);
                            }
                        } catch (Exception e) {
                            LogUtils.getInstance().logError(TAG, "处理ResponseBody.bytes失败", e);
                        }
                    }
                });

            LogUtils.getInstance().log(TAG, "OkHttp ResponseBody Hook 成功");
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "ResponseBody Hook 失败: " + e.getMessage());
            // 禁用可能导致卡顿的备用方案
            // hookOkHttpClient(lpparam);
        }
    }

    /**
     * 从响应内容中提取 URL（备用方案）
     * 通过检查响应内容是否包含视频信息来判断
     */
    private static String extractUrlFromResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return null;

        try {
            // 检查是否包含视频详情信息
            // B站 API 返回格式: {"data":{"bvid":"xxx","cid":123,...}}
            if (responseBody.contains("\"bvid\"")) {
                // 提取 bvid
                String bvid = extractJsonValue(responseBody, "bvid");
                if (bvid != null && bvid.startsWith("BV")) {
                    LogUtils.getInstance().logDebug(TAG, "从响应中提取到 bvid: " + bvid);
                    // 同时尝试提取 cid
                    String cid = extractJsonValue(responseBody, "cid");
                    if (cid != null) {
                        LogUtils.getInstance().logDebug(TAG, "从响应中提取到 cid: " + cid);
                        // 更新视频信息
                        updateLastKnownBvid(bvid);
                        updateLastKnownCid(cid);
                    }
                    // 构造一个虚拟 URL 用于后续处理
                    return "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid;
                }
            }

            // 检查是否包含 aid 和 cid（另一种格式）
            if (responseBody.contains("\"aid\"") && responseBody.contains("\"cid\"")) {
                String aid = extractJsonValue(responseBody, "aid");
                String cid = extractJsonValue(responseBody, "cid");
                if (cid != null) {
                    LogUtils.getInstance().logDebug(TAG, "从响应中提取到 cid: " + cid);
                    updateLastKnownCid(cid);
                    if (aid != null) {
                        return "https://api.bilibili.com/x/web-interface/view?aid=" + aid;
                    }
                }
            }

            // 检查是否包含播放 URL 信息
            if (responseBody.contains("\"playurl\"") || responseBody.contains("\"play_url\"") || responseBody.contains("\"dash\"")) {
                // 尝试提取 cid
                String cid = extractJsonValue(responseBody, "cid");
                if (cid != null) {
                    LogUtils.getInstance().logDebug(TAG, "从播放URL响应中提取到 cid: " + cid);
                    updateLastKnownCid(cid);
                    return "https://api.bilibili.com/x/player/playurl?cid=" + cid;
                }
            }

        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "从响应内容提取 URL 失败: " + e.getMessage());
        }

        return null;
    }

    /**
     * 从 JSON 字符串中提取指定字段的值
     */
    private static String extractJsonValue(String json, String key) {
        try {
            String searchKey = "\"" + key + "\":";
            int startIndex = json.indexOf(searchKey);
            if (startIndex == -1) {
                // 尝试另一种格式
                searchKey = "\"" + key + "\"";
                startIndex = json.indexOf(searchKey);
                if (startIndex == -1) return null;
                // 找到冒号
                int colonIndex = json.indexOf(":", startIndex);
                if (colonIndex == -1) return null;
                startIndex = colonIndex;
            } else {
                startIndex += searchKey.length();
            }

            // 跳过空白
            while (startIndex < json.length() && Character.isWhitespace(json.charAt(startIndex))) {
                startIndex++;
            }

            if (startIndex >= json.length()) return null;

            char firstChar = json.charAt(startIndex);
            if (firstChar == '"') {
                // 字符串值
                int endIndex = json.indexOf('"', startIndex + 1);
                if (endIndex == -1) return null;
                return json.substring(startIndex + 1, endIndex);
            } else if (firstChar == '-' || Character.isDigit(firstChar)) {
                // 数字值
                int endIndex = startIndex;
                while (endIndex < json.length() && (Character.isDigit(json.charAt(endIndex)) || json.charAt(endIndex) == '.' || json.charAt(endIndex) == '-')) {
                    endIndex++;
                }
                return json.substring(startIndex, endIndex);
            } else if (firstChar == 't' || firstChar == 'f') {
                // 布尔值
                return json.substring(startIndex, startIndex + (firstChar == 't' ? 4 : 5));
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    private static Object getResponseFromBody(Object body) {
        // 方法1：尝试获取 response 字段
        try {
            Field responseField = body.getClass().getDeclaredField("response");
            responseField.setAccessible(true);
            Object response = responseField.get(body);
            if (response != null) {
                LogUtils.getInstance().logDebug(TAG, "通过 response 字段获取成功");
                return response;
            }
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "获取 response 字段失败: " + e.getMessage());
        }

        // 方法2：遍历所有字段
        try {
            for (Field field : body.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(body);
                if (value != null && value.getClass().getName().contains("Response")) {
                    LogUtils.getInstance().logDebug(TAG, "通过遍历字段获取成功: " + field.getName());
                    return value;
                }
            }
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "遍历字段失败: " + e.getMessage());
        }

        // 方法3：尝试调用 response() 方法
        try {
            Method responseMethod = body.getClass().getMethod("response");
            Object response = responseMethod.invoke(body);
            if (response != null) {
                LogUtils.getInstance().logDebug(TAG, "通过 response() 方法获取成功");
                return response;
            }
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "调用 response() 方法失败: " + e.getMessage());
        }

        LogUtils.getInstance().logDebug(TAG, "getResponseFromBody 返回 null");
        return null;
    }
    
    private static boolean shouldProcessUrl(String url) {
        if (!url.contains("bilibili.com")) return false;
        
        // 视频详情相关接口
        if (url.contains("/x/web-interface/view") ||
            url.contains("/x/player/") ||
            url.contains("/x/player/wbi/") ||
            url.contains("/x/v2/view") ||
            url.contains("/pgc/view/web/season") ||
            url.contains("bvid=") ||
            url.contains("aid=")) {
            return true;
        }
        
        // 播放地址相关接口（包含cid）
        if (url.contains("/x/playurl/") ||
            url.contains("/x/player/playurl") ||
            url.contains("cid=")) {
            return true;
        }
        
        return false;
    }
    
    private static void hookOkHttpClient(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> clientClass = XposedHelpers.findClass("okhttp3.OkHttpClient", lpparam.classLoader);
            Class<?> requestClass = XposedHelpers.findClass("okhttp3.Request", lpparam.classLoader);
            
            XposedHelpers.findAndHookMethod(clientClass, "newCall", requestClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object call = param.getResult();
                            if (call == null) return;
                            
                            Class<?> callClass = call.getClass();
                            String callClassName = callClass.getName();
                            
                            if (!hookedCallClasses.contains(callClassName)) {
                                hookedCallClasses.add(callClassName);
                                LogUtils.getInstance().log(TAG, "发现Call类: " + callClassName);
                                
                                try {
                                    java.lang.reflect.Method executeMethod = callClass.getMethod("execute");
                                    
                                    if (java.lang.reflect.Modifier.isAbstract(executeMethod.getModifiers())) {
                                        LogUtils.getInstance().logDebug(TAG, "跳过抽象Call类: " + callClassName);
                                    } else {
                                        LogUtils.getInstance().log(TAG, "动态Hook Call类: " + callClassName);
                                        
                                        XposedHelpers.findAndHookMethod(callClass, "execute",
                                            new XC_MethodHook() {
                                                @Override
                                                protected void afterHookedMethod(MethodHookParam param) {
                                                    try {
                                                        Object response = param.getResult();
                                                        if (response == null) return;
                                                        
                                                        Object request = XposedHelpers.callMethod(response, "request");
                                                        Object url = XposedHelpers.callMethod(request, "url");
                                                        String urlString = url.toString();
                                                        
                                                        if (shouldProcessUrl(urlString)) {
                                                            LogUtils.getInstance().log(TAG, "Call.execute拦截: " + urlString);
                                                            
                                                            Object body = XposedHelpers.callMethod(response, "body");
                                                            if (body != null) {
                                                                String responseBody = readOkHttpBody(body);
                                                                if (responseBody != null && !responseBody.isEmpty()) {
                                                                    parseVideoInfoFromResponse(urlString, responseBody);
                                                                }
                                                            }
                                                        }
                                                    } catch (Exception e) {
                                                        LogUtils.getInstance().logDebug(TAG, "处理execute响应失败: " + e.getMessage());
                                                    }
                                                }
                                            });
                                    }
                                } catch (NoSuchMethodException e) {
                                    LogUtils.getInstance().logDebug(TAG, "Call类没有execute方法: " + callClassName);
                                }
                            }
                        } catch (Exception e) {
                            LogUtils.getInstance().logDebug(TAG, "动态Hook Call失败: " + e.getMessage());
                        }
                    }
                });
            
            LogUtils.getInstance().log(TAG, "OkHttpClient Hook 成功（备选方案）");
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "OkHttpClient Hook 失败: " + e.getMessage());
        }
    }
    
    // 记录已经Hook过的Call类，避免重复Hook
    private static final Set<String> hookedCallClasses = ConcurrentHashMap.newKeySet();
    
    /**
     * 查找OkHttp的Call实现类
     */
    private static Class<?> findOkHttpCallClass(ClassLoader classLoader) {
        // 首先尝试找具体的实现类，而不是接口
        String[] possibleClasses = {
            "okhttp3.RealCall",
            "okhttp3.internal.connection.RealCall",
            "okhttp3.internal.RealCall",
            "com.squareup.okhttp.internal.http.RealCall"
        };
        
        for (String className : possibleClasses) {
            try {
                Class<?> clazz = Class.forName(className, false, classLoader);
                // 检查是否有execute方法且不是抽象方法
                try {
                    java.lang.reflect.Method executeMethod = clazz.getMethod("execute");
                    if (!java.lang.reflect.Modifier.isAbstract(executeMethod.getModifiers())) {
                        LogUtils.getInstance().log(TAG, "找到OkHttp实现类: " + className);
                        return clazz;
                    }
                } catch (NoSuchMethodException e) {
                    // 继续查找
                }
            } catch (ClassNotFoundException e) {
                // 继续查找下一个
            }
        }
        
        // 如果没找到具体实现类，尝试通过接口找到实现类
        try {
            Class<?> callInterface = Class.forName("okhttp3.Call", false, classLoader);
            // 获取所有已加载的类（这个方法可能不适用于所有Android版本）
            // 作为备选，我们尝试Hook OkHttpClient的newCall方法
            LogUtils.getInstance().log(TAG, "未找到RealCall实现类，将尝试Hook OkHttpClient");
            return null;
        } catch (ClassNotFoundException e) {
            // 忽略
        }
        
        return null;
    }
    
    /**
     * 读取OkHttp ResponseBody
     */
    private static String readOkHttpBody(Object body) {
        try {
            // 获取bytes
            byte[] bytes = (byte[]) XposedHelpers.callMethod(body, "bytes");
            if (bytes != null && bytes.length > 0) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            return null;
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "读取OkHttp body失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * Hook HttpURLConnection
     */
    private static void hookHttpURLConnection(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook URLConnection的getInputStream
            XposedHelpers.findAndHookMethod(
                "java.net.URLConnection", 
                lpparam.classLoader,
                "getInputStream",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object connection = param.thisObject;
                            if (!(connection instanceof HttpURLConnection)) {
                                return;
                            }
                            
                            // 获取URL
                            Method getURLMethod = connection.getClass().getMethod("getURL");
                            URL url = (URL) getURLMethod.invoke(connection);
                            String urlString = url.toString();
                            
                            // 检查是否是Bilibili视频API
                            if (urlString.contains("bilibili.com") &&
                                (urlString.contains("/x/web-interface/view") ||
                                 urlString.contains("/x/player/"))) {
                                
                                LogUtils.getInstance().log(TAG, "HttpURLConnection拦截到API: " + urlString);
                                
                                // 获取响应
                                InputStream inputStream = (InputStream) param.getResult();
                                if (inputStream != null) {
                                    // 读取响应内容
                                    String responseBody = readInputStream(inputStream);
                                    
                                    // 解析视频信息
                                    if (responseBody != null && !responseBody.isEmpty()) {
                                        parseVideoInfoFromResponse(urlString, responseBody);
                                    }
                                    
                                    // 返回新的InputStream
                                    param.setResult(new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
                                }
                            }
                        } catch (Exception e) {
                            LogUtils.getInstance().logDebug(TAG, "处理连接失败: " + e.getMessage());
                        }
                    }
                });
            
            LogUtils.getInstance().log(TAG, "HttpURLConnection Hook 成功");
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "HttpURLConnection Hook 失败: " + e.getMessage());
        }
    }

    /**
     * 读取InputStream内容
     */
    private static String readInputStream(InputStream inputStream) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从API响应解析视频信息
     */
    private static void parseVideoInfoFromResponse(String url, String responseBody) {
        try {
            LogUtils.getInstance().logDebug(TAG, "解析响应: " + url.substring(0, Math.min(100, url.length())));
            
            JSONObject json = new JSONObject(responseBody);
            
            // 检查是否成功
            int code = json.optInt("code", -1);
            if (code != 0) {
                LogUtils.getInstance().logDebug(TAG, "API返回错误码: " + code);
                return;
            }
            
            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                LogUtils.getInstance().logDebug(TAG, "响应中无data字段");
                return;
            }
            
            String bvid = null;
            String cid = null;
            
            // 1. 从视频详情API解析
            if (url.contains("/x/web-interface/view")) {
                bvid = data.optString("bvid", null);
                long cidLong = data.optLong("cid", 0);
                if (cidLong > 0) {
                    cid = String.valueOf(cidLong);
                }

                // 如果没有cid，尝试从pages获取
                if (cid == null || cid.equals("0")) {
                    JSONArray pages = data.optJSONArray("pages");
                    if (pages != null && pages.length() > 0) {
                        JSONObject firstPage = pages.optJSONObject(0);
                        if (firstPage != null) {
                            cidLong = firstPage.optLong("cid", 0);
                            if (cidLong > 0) {
                                cid = String.valueOf(cidLong);
                            }
                        }
                    }
                }

                // 如果没有bvid，尝试从URL参数获取aid并转换
                if (bvid == null || bvid.isEmpty()) {
                    Uri uri = Uri.parse(url);
                    String aidParam = uri.getQueryParameter("aid");
                    if (aidParam != null && !aidParam.isEmpty()) {
                        // 尝试从响应中获取bvid（可能aid对应的视频有bvid）
                        bvid = data.optString("bvid", null);
                        if (bvid == null || bvid.isEmpty()) {
                            // 使用 BvidConverter 将 aid 转换为 bvid
                            try {
                                long aid = Long.parseLong(aidParam);
                                bvid = BvidConverter.aidToBvid(aid);
                                LogUtils.getInstance().log(TAG, "将 aid 转换为 bvid: " + aidParam + " -> " + bvid);
                            } catch (NumberFormatException e) {
                                LogUtils.getInstance().logDebug(TAG, "aid 格式错误: " + aidParam);
                            }
                        }
                    }
                }
            }
            
            // 2. 从播放地址API解析
            if (url.contains("/x/player/")) {
                // 从URL参数获取bvid和cid
                Uri uri = Uri.parse(url);
                bvid = uri.getQueryParameter("bvid");
                String cidParam = uri.getQueryParameter("cid");
                if (cidParam != null) {
                    cid = cidParam;
                }
            }
            
            // 更新视频信息
            if (bvid != null && !bvid.isEmpty() && bvid.startsWith("BV")) {
                updateLastKnownBvid(bvid);
                LogUtils.getInstance().log(TAG, "从API响应获取 BV: " + bvid);
            }
            
            if (cid != null && !cid.isEmpty() && !cid.equals("0")) {
                updateLastKnownCid(cid);
                LogUtils.getInstance().log(TAG, "从API响应获取 CID: " + cid);
            }
            
            // 如果获取到完整信息，更新当前播放器状态
            if (bvid != null && !bvid.isEmpty()) {
                VideoInfo info = new VideoInfo();
                info.setBvid(bvid);
                // 使用当前获取的 cid，如果没有则使用 lastKnownVideoInfo 中的
                String finalCid = (cid != null && !cid.isEmpty() && !cid.equals("0")) 
                    ? cid : lastKnownVideoInfo.getCid();
                if (finalCid != null && !finalCid.isEmpty()) {
                    info.setCid(finalCid);
                }
                currentVideoInfo.set(info);

                // 更新活跃播放器状态
                PlayerState state = activeState.get();
                if (state != null && !state.videoInfo.isComplete()) {
                    state.videoInfo.setBvid(bvid);
                    if (finalCid != null) {
                        state.videoInfo.setCid(finalCid);
                    }
                    loadSegmentsIfReady(state);
                } else if (finalCid != null) {
                    // 如果没有活跃的播放器状态，直接加载片段
                    loadSegmentsWithInfo(bvid, finalCid);
                }
            }
            
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "解析响应失败: " + e.getMessage());
        }
    }

    /**
     * Hook播放器类
     */
    private static void hookPlayerClasses(XC_LoadPackage.LoadPackageParam lpparam) {
        // 尝试 Hook 多个播放器类
        String[] playerClasses = {
            "tv.danmaku.ijk.media.player.IjkMediaPlayer",
            "com.bilibili.player.IjkMediaPlayer",
            "tv.danmaku.bili.player.core.IjkMediaPlayer",
            "com.google.android.exoplayer2.ExoPlayer",
            "com.google.android.exoplayer2.SimpleExoPlayer",
            "android.media.MediaPlayer",
            "com.bilibili.player.base.BaseMediaPlayer",
            "com.bilibili.player.core.player.BaseMediaPlayer"
        };
        
        boolean hooked = false;
        for (String className : playerClasses) {
            try {
                Class<?> playerClass = XposedHelpers.findClass(className, lpparam.classLoader);
                hookPlayerMethods(playerClass);
                LogUtils.getInstance().log(TAG, "播放器 Hook 成功: " + className);
                hooked = true;
                break;
            } catch (Exception e) {
                LogUtils.getInstance().logDebug(TAG, className + " Hook 失败: " + e.getMessage());
            }
        }
        
        if (!hooked) {
            LogUtils.getInstance().logDebug(TAG, "所有播放器类 Hook 失败，尝试通用方法");
            // 尝试 Hook 所有可能的播放器类
            hookGenericPlayer(lpparam);
        }
    }
    
    /**
     * 通用播放器 Hook 方法
     */
    private static void hookGenericPlayer(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook 所有包含 "Player" 的类
            XposedHelpers.findAndHookMethod("android.media.MediaPlayer", lpparam.classLoader,
                "start",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        LogUtils.getInstance().log(TAG, "MediaPlayer.start() 被调用");
                        onPlayerStart(param.thisObject);
                    }
                });
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "通用播放器 Hook 失败: " + e.getMessage());
        }
    }
    
    /**
     * 播放器开始播放时的处理
     */
    private static void onPlayerStart(Object player) {
        try {
            PlayerState state = getOrCreateState(player);
            state.isActive = true;
            state.isPaused = false;
            activePlayer.set(player);
            activeState.set(state);
            
            LogUtils.getInstance().log(TAG, "播放器开始播放 (通用)");
            
            // 启动定时检查
            startPeriodicCheck();
            
            // 加载片段
            VideoInfo currentInfo = currentVideoInfo.get();
            if (currentInfo != null && currentInfo.isComplete()) {
                state.videoInfo.setBvid(currentInfo.getBvid());
                state.videoInfo.setCid(currentInfo.getCid());
                LogUtils.getInstance().log(TAG, "使用当前视频信息: " + state.videoInfo);
                loadSegmentsIfReady(state);
            } else if (lastKnownVideoInfo.isComplete()) {
                state.videoInfo.setBvid(lastKnownVideoInfo.getBvid());
                state.videoInfo.setCid(lastKnownVideoInfo.getCid());
                LogUtils.getInstance().log(TAG, "使用上次已知的视频信息: " + state.videoInfo);
                loadSegmentsIfReady(state);
            }
        } catch (Exception e) {
            LogUtils.getInstance().logError(TAG, "播放器启动处理失败", e);
        }
    }

    private static void hookPlayerMethods(Class<?> playerClass) {
        XposedHelpers.findAndHookMethod(playerClass, "setDataSource", String.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String url = (String) param.args[0];
                    if (url != null) {
                        LogUtils.getInstance().log(TAG, "setDataSource(String): " + url.substring(0, Math.min(200, url.length())));
                        extractCidFromPlayUrl(url);
                    }
                }
            });
        
        try {
            Class<?> contextClass = XposedHelpers.findClass("android.content.Context", playerClass.getClassLoader());
            Class<?> uriClass = XposedHelpers.findClass("android.net.Uri", playerClass.getClassLoader());
            XposedHelpers.findAndHookMethod(playerClass, "setDataSource", contextClass, uriClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object uri = param.args[1];
                        if (uri != null) {
                            String url = uri.toString();
                            LogUtils.getInstance().log(TAG, "setDataSource(Context,Uri): " + url.substring(0, Math.min(200, url.length())));
                            extractCidFromPlayUrl(url);
                        }
                    }
                });
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "Hook setDataSource(Context,Uri)失败: " + e.getMessage());
        }
        
        XposedHelpers.findAndHookMethod(playerClass, "prepareAsync",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object player = param.thisObject;
                    LogUtils.getInstance().log(TAG, "播放器prepareAsync");
                    
                    try {
                        Field dataSourceField = player.getClass().getDeclaredField("mDataSource");
                        dataSourceField.setAccessible(true);
                        Object dataSource = dataSourceField.get(player);
                        if (dataSource != null) {
                            String url = dataSource.toString();
                            LogUtils.getInstance().log(TAG, "从mDataSource获取: " + url.substring(0, Math.min(200, url.length())));
                            extractCidFromPlayUrl(url);
                        }
                    } catch (Exception e) {
                        LogUtils.getInstance().logDebug(TAG, "获取mDataSource失败: " + e.getMessage());
                    }
                }
            });
        
        XposedHelpers.findAndHookMethod(playerClass, "start",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object player = param.thisObject;
                    PlayerState state = getOrCreateState(player);
                    state.isActive = true;
                    state.isPaused = false;
                    activePlayer.set(player);
                    activeState.set(state);

                    LogUtils.getInstance().log(TAG, "播放器开始播放");

                    VideoInfo currentInfo = currentVideoInfo.get();
                    if (currentInfo != null && currentInfo.isComplete()) {
                        state.videoInfo.setBvid(currentInfo.getBvid());
                        state.videoInfo.setCid(currentInfo.getCid());
                        LogUtils.getInstance().log(TAG, "使用当前视频信息: " + state.videoInfo);
                        loadSegmentsIfReady(state);
                    } else if (lastKnownVideoInfo.isComplete()) {
                        state.videoInfo.setBvid(lastKnownVideoInfo.getBvid());
                        state.videoInfo.setCid(lastKnownVideoInfo.getCid());
                        LogUtils.getInstance().log(TAG, "使用上次已知的视频信息: " + state.videoInfo);
                        loadSegmentsIfReady(state);
                    } else {
                        LogUtils.getInstance().logDebug(TAG, "等待视频信息从网络请求获取...");
                    }
                }
            });

        // Hook pause方法
        XposedHelpers.findAndHookMethod(playerClass, "pause",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object player = param.thisObject;
                    PlayerState state = playerStates.get(player);
                    if (state != null) {
                        state.isPaused = true;
                    }
                }
            });

        // Hook seekTo方法
        XposedHelpers.findAndHookMethod(playerClass, "seekTo", long.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object player = param.thisObject;
                    PlayerState state = playerStates.get(player);
                    if (state != null) {
                        state.skippedSegments.clear();
                        LogUtils.getInstance().logDebug(TAG, "用户跳转，清除已跳过记录");
                    }
                }
            });

        // Hook release方法
        XposedHelpers.findAndHookMethod(playerClass, "release",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object player = param.thisObject;
                    cleanupPlayer(player);
                }
            });

        // Hook getCurrentPosition方法
        XposedHelpers.findAndHookMethod(playerClass, "getCurrentPosition",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object player = param.thisObject;
                    PlayerState state = playerStates.get(player);
                    if (state != null) {
                        state.currentPosition = (Long) param.getResult();
                    }
                }
            });
    }

    /**
     * Hook视频相关Activity
     */
    private static void hookVideoActivities(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook Activity的onCreate
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                "onCreate", Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        String className = activity.getClass().getName();

                        // 只处理视频相关Activity
                        if (isVideoActivity(className)) {
                            LogUtils.getInstance().log(TAG, "视频Activity创建: " + className);
                            extractVideoInfoFromIntent(activity);
                        }
                    }
                });

            // Hook Activity的onResume - 添加提交按钮（只在播放器全屏时）
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                "onResume",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        String className = activity.getClass().getName();

                        // 只处理视频详情页
                        LogUtils.getInstance().logDebug(TAG, "Activity 恢复: " + className);
                        if (isVideoDetailActivity(className)) {
                            LogUtils.getInstance().log(TAG, "视频详情页恢复: " + className);

                            // 延迟检查并添加按钮（多次尝试，等待播放器加载）
                            mainHandler.postDelayed(() -> {
                                tryAddSubmitButton(activity, 0);
                            }, 500);
                        } else {
                            LogUtils.getInstance().logDebug(TAG, "不是视频详情页，跳过: " + className);
                        }
                    }
                });

            // Hook Activity的onDestroy - 移除提交按钮
            XposedHelpers.findAndHookMethod("android.app.Activity", lpparam.classLoader,
                "onDestroy",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Activity activity = (Activity) param.thisObject;
                        removeSubmitButton(activity);
                    }
                });

            LogUtils.getInstance().log(TAG, "Activity Hook 完成");
        } catch (Exception e) {
            LogUtils.getInstance().logError(TAG, "Activity Hook 失败", e);
        }
    }

    /**
     * 尝试添加提交按钮（支持多次重试）
     */
    private static void tryAddSubmitButton(Activity activity, int retryCount) {
        if (retryCount > 5) {
            LogUtils.getInstance().logDebug(TAG, "尝试添加按钮次数过多，放弃");
            return;
        }
        
        // 检查是否已添加
        if (submitButtons.containsKey(activity)) {
            return;
        }
        
        // 检查是否处于播放器全屏状态
        if (!isPlayerFullscreen(activity)) {
            LogUtils.getInstance().logDebug(TAG, "播放器未全屏，等待重试 (" + retryCount + ")");
            mainHandler.postDelayed(() -> tryAddSubmitButton(activity, retryCount + 1), 1000);
            return;
        }
        
        // 尝试添加按钮
        boolean success = addSubmitButtonInternal(activity);
        if (!success && retryCount < 5) {
            LogUtils.getInstance().logDebug(TAG, "添加按钮失败，等待重试 (" + retryCount + ")");
            mainHandler.postDelayed(() -> tryAddSubmitButton(activity, retryCount + 1), 1000);
        }
    }
    
    /**
     * 内部添加按钮方法
     * 替换投币按钮为提交片段按钮
     */
    private static boolean addSubmitButtonInternal(Activity activity) {
        try {
            // 在 DecorView 中查找投币按钮
            android.view.ViewGroup decorView = (android.view.ViewGroup) activity.getWindow().getDecorView();
            android.view.View coinButton = findCoinButton(decorView);
            
            if (coinButton == null) {
                LogUtils.getInstance().logDebug(TAG, "未找到投币按钮，等待重试");
                return false;
            }
            
            // 获取投币按钮的父布局
            android.view.ViewGroup controlBar = (android.view.ViewGroup) coinButton.getParent();
            if (controlBar == null) {
                LogUtils.getInstance().logDebug(TAG, "投币按钮没有父布局");
                return false;
            }

            // 获取投币按钮的位置和大小
            int coinIndex = -1;
            for (int i = 0; i < controlBar.getChildCount(); i++) {
                if (controlBar.getChildAt(i) == coinButton) {
                    coinIndex = i;
                    break;
                }
            }

            if (coinIndex == -1) {
                LogUtils.getInstance().logDebug(TAG, "未找到投币按钮索引");
                return false;
            }
            
            // 保存投币按钮的布局参数
            android.view.ViewGroup.LayoutParams coinParams = coinButton.getLayoutParams();
            
            // 移除投币按钮
            controlBar.removeViewAt(coinIndex);
            
            // 创建 SponsorBlock 按钮
            android.widget.ImageButton submitButton = new android.widget.ImageButton(activity);
            submitButton.setImageBitmap(createSponsorBlockIcon(activity));
            submitButton.setBackground(null);
            submitButton.setPadding(coinButton.getPaddingLeft(), coinButton.getPaddingTop(), 
                                   coinButton.getPaddingRight(), coinButton.getPaddingBottom());
            submitButton.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            
            // 设置点击事件
            submitButton.setOnClickListener(v -> {
                showSubmitDialog(activity);
            });
            
            // 设置长按提示
            submitButton.setOnLongClickListener(v -> {
                android.widget.Toast.makeText(activity, "提交空降片段", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            });
            
            // 在投币按钮原来的位置添加 SponsorBlock 按钮
            controlBar.addView(submitButton, coinIndex, coinParams);
            submitButtons.put(activity, submitButton);
            
            LogUtils.getInstance().log(TAG, "已替换投币按钮为 SponsorBlock 提交按钮");
            return true;
        } catch (Exception e) {
            LogUtils.getInstance().logError(TAG, "替换投币按钮失败", e);
            return false;
        }
    }
    
    /**
     * 查找投币按钮
     */
    private static android.view.View findCoinButton(android.view.ViewGroup root) {
        String coinButtonClass = "com.bilibili.app.gemini.player.widget.coin.GeminiPlayerCoinWidget";
        
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            String childClassName = child.getClass().getName();
            
            if (childClassName.equals(coinButtonClass)) {
                LogUtils.getInstance().log(TAG, "找到投币按钮");
                return child;
            }
            
            if (child instanceof android.view.ViewGroup) {
                android.view.View result = findCoinButton((android.view.ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * 通过类名查找播放器控制栏
     * 查找包含 GeminiPlayerLikeWidget 等播放器内部按钮的布局
     */
    private static android.view.ViewGroup findPlayerControlBarByClassName(android.view.ViewGroup root) {
        // 播放器内部按钮类名
        String[] playerWidgetClasses = {
            "com.bilibili.app.gemini.player.widget.like.GeminiPlayerLikeWidget",
            "com.bilibili.app.gemini.player.widget.coin.GeminiPlayerCoinWidget",
            "com.bilibili.app.gemini.player.widget.share.GeminiPlayerShareIconWidget"
        };
        
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            
            // 检查子视图是否是播放器内部按钮
            String childClassName = child.getClass().getName();
            for (String widgetClass : playerWidgetClasses) {
                if (childClassName.equals(widgetClass)) {
                    // 找到了播放器内部按钮，返回其父布局
                    if (child.getParent() instanceof android.view.ViewGroup) {
                        LogUtils.getInstance().log(TAG, "找到播放器内部按钮: " + childClassName);
                        return (android.view.ViewGroup) child.getParent();
                    }
                }
            }
            
            // 递归查找
            if (child instanceof android.view.ViewGroup) {
                android.view.ViewGroup result = findPlayerControlBarByClassName((android.view.ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * 在播放器界面添加 SponsorBlock 提交按钮
     * 放在右上角点赞一栏
     */
    private static void addSubmitButton(Activity activity) {
        try {
            // 检查是否已添加
            if (submitButtons.containsKey(activity)) return;

            // 查找播放器控制栏（点赞按钮所在的布局）
            android.view.ViewGroup targetParent = findPlayerControlBar(activity);

            if (targetParent == null) {
                LogUtils.getInstance().logDebug(TAG, "未找到播放器控制栏");
                return;
            }

            // 创建 SponsorBlock 风格图标按钮
            android.widget.ImageButton submitButton = new android.widget.ImageButton(activity);
            submitButton.setImageBitmap(createSponsorBlockIcon(activity));
            submitButton.setBackground(null); // 透明背景，图标自带背景
            submitButton.setPadding(8, 8, 8, 8);
            submitButton.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);

            // 设置点击事件
            submitButton.setOnClickListener(v -> {
                showSubmitDialog(activity);
            });

            // 设置长按提示
            submitButton.setOnLongClickListener(v -> {
                android.widget.Toast.makeText(activity, "提交空降片段", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            });

            // 添加到控制栏，放在点赞按钮左边
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                (int) (36 * activity.getResources().getDisplayMetrics().density),
                (int) (36 * activity.getResources().getDisplayMetrics().density)
            );
            params.setMargins(4, 0, 4, 0);

            // 找到点赞按钮的位置，插入到它左边
            int likeIndex = findLikeButtonIndex(targetParent);
            if (likeIndex >= 0) {
                targetParent.addView(submitButton, likeIndex, params);
            } else {
                targetParent.addView(submitButton, 0, params);
            }

            submitButtons.put(activity, submitButton);

            LogUtils.getInstance().log(TAG, "已添加 SponsorBlock 提交按钮到播放器控制栏");
        } catch (Exception e) {
            LogUtils.getInstance().logError(TAG, "添加提交按钮失败", e);
        }
    }
    
    /**
     * 查找播放器控制栏（使用更宽松的条件）
     */
    private static android.view.ViewGroup findPlayerControlBar(Activity activity) {
        android.view.ViewGroup decorView = (android.view.ViewGroup) activity.getWindow().getDecorView();
        
        // 方法1：尝试常见的控制栏ID
        String[] controlIds = {
            "player_controller",
            "video_player_controller",
            "player_control",
            "controller_container",
            "danmaku_player_controller",
            "video_player_control",
            "bottom_controller",
            "player_bottom_bar"
        };
        
        for (String idName : controlIds) {
            int id = activity.getResources().getIdentifier(idName, "id", activity.getPackageName());
            if (id != 0) {
                android.view.View view = decorView.findViewById(id);
                if (view instanceof android.view.ViewGroup && view.getVisibility() == android.view.View.VISIBLE) {
                    LogUtils.getInstance().log(TAG, "找到播放器控制栏(ID): " + idName);
                    return (android.view.ViewGroup) view;
                }
            }
        }
        
        // 方法2：遍历DecorView查找播放器控制栏
        return findPlayerControlBarInView(decorView);
    }
    
    /**
     * 在视图中递归查找播放器控制栏
     */
    private static android.view.ViewGroup findPlayerControlBarInView(android.view.ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            
            // 检查是否是控制栏（通过类名或包含的子视图）
            if (child instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) child;
                String className = child.getClass().getName().toLowerCase();
                
                // 检查类名是否包含控制栏相关关键词
                boolean isControlBar = className.contains("controller") || 
                                       className.contains("control") ||
                                       className.contains("toolbar") ||
                                       className.contains("bottombar") ||
                                       className.contains("actionbar");
                
                // 检查是否包含点赞按钮
                boolean hasLikeButton = false;
                for (int j = 0; j < group.getChildCount(); j++) {
                    android.view.View subChild = group.getChildAt(j);
                    Object desc = subChild.getContentDescription();
                    if (desc != null) {
                        String descStr = desc.toString().toLowerCase();
                        if (descStr.contains("赞") || descStr.contains("like") || 
                            descStr.contains("thumb") || descStr.contains("点赞")) {
                            hasLikeButton = true;
                            break;
                        }
                    }
                    Object tag = subChild.getTag();
                    if (tag != null) {
                        String tagStr = tag.toString().toLowerCase();
                        if (tagStr.contains("like") || tagStr.contains("zan")) {
                            hasLikeButton = true;
                            break;
                        }
                    }
                }
                
                // 如果是控制栏且包含点赞按钮，则返回
                if (isControlBar && hasLikeButton && child.getVisibility() == android.view.View.VISIBLE) {
                    LogUtils.getInstance().log(TAG, "找到播放器控制栏(遍历): " + className);
                    return group;
                }
                
                // 递归查找
                android.view.ViewGroup result = findPlayerControlBarInView(group);
                if (result != null) return result;
            }
        }
        return null;
    }
    
    /**
     * 检查视图是否在播放器视图内
     */
    private static boolean isInPlayerView(android.view.View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof android.view.View) {
                String className = parent.getClass().getName().toLowerCase();
                // 如果父视图是播放器相关视图
                if (className.contains("player") || className.contains("video") ||
                    className.contains("danmaku") || className.contains("ijk")) {
                    return true;
                }
                // 如果父视图是详情页相关视图，则不在播放器内
                if (className.contains("detail") || className.contains("intro") ||
                    className.contains("desc") || className.contains("info")) {
                    return false;
                }
            }
            parent = parent.getParent();
        }
        return false;
    }
    
    /**
     * 在播放器视图内查找包含点赞按钮的父布局
     */
    private static android.view.ViewGroup findPlayerLikeParent(android.view.ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            if (child instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) child;
                String className = child.getClass().getName().toLowerCase();
                
                // 只处理播放器相关的视图组
                boolean isPlayerRelated = className.contains("player") || 
                                          className.contains("video") ||
                                          className.contains("danmaku") ||
                                          className.contains("controller");
                
                if (isPlayerRelated) {
                    // 检查是否包含点赞按钮
                    for (int j = 0; j < group.getChildCount(); j++) {
                        android.view.View subChild = group.getChildAt(j);
                        Object desc = subChild.getContentDescription();
                        if (desc != null) {
                            String descStr = desc.toString().toLowerCase();
                            if (descStr.contains("赞") || descStr.contains("like") || 
                                descStr.contains("thumb") || descStr.contains("点赞")) {
                                LogUtils.getInstance().log(TAG, "在播放器内找到点赞按钮");
                                return group;
                            }
                        }
                        Object tag = subChild.getTag();
                        if (tag != null) {
                            String tagStr = tag.toString().toLowerCase();
                            if (tagStr.contains("like") || tagStr.contains("zan") || tagStr.contains("thumb")) {
                                LogUtils.getInstance().log(TAG, "在播放器内找到点赞按钮(tag)");
                                return group;
                            }
                        }
                    }
                }
                
                // 递归查找，但只在播放器相关视图中查找
                if (isPlayerRelated || className.contains("decor") || className.contains("content")) {
                    android.view.ViewGroup result = findPlayerLikeParent(group);
                    if (result != null) return result;
                }
            }
        }
        return null;
    }
    
    /**
     * 查找点赞按钮的索引
     */
    private static int findLikeButtonIndex(android.view.ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            android.view.View child = parent.getChildAt(i);
            Object desc = child.getContentDescription();
            if (desc != null) {
                String descStr = desc.toString().toLowerCase();
                if (descStr.contains("赞") || descStr.contains("like") || 
                    descStr.contains("thumb") || descStr.contains("点赞")) {
                    return i;
                }
            }
            // 检查tag
            Object tag = child.getTag();
            if (tag != null) {
                String tagStr = tag.toString().toLowerCase();
                if (tagStr.contains("like") || tagStr.contains("zan") || tagStr.contains("thumb")) {
                    return i;
                }
            }
        }
        return -1;
    }
    
    /**
     * 查找包含点赞按钮的父布局
     */
    private static android.view.ViewGroup findLikeParent(android.view.ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            if (child instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) child;
                
                // 检查是否包含点赞按钮
                for (int j = 0; j < group.getChildCount(); j++) {
                    android.view.View subChild = group.getChildAt(j);
                    Object desc = subChild.getContentDescription();
                    if (desc != null) {
                        String descStr = desc.toString().toLowerCase();
                        if (descStr.contains("赞") || descStr.contains("like") || 
                            descStr.contains("thumb") || descStr.contains("点赞")) {
                            return group;
                        }
                    }
                    Object tag = subChild.getTag();
                    if (tag != null) {
                        String tagStr = tag.toString().toLowerCase();
                        if (tagStr.contains("like") || tagStr.contains("zan") || tagStr.contains("thumb")) {
                            return group;
                        }
                    }
                }
                
                // 递归查找
                android.view.ViewGroup result = findLikeParent(group);
                if (result != null) return result;
            }
        }
        return null;
    }
    
    /**
     * 检查播放器是否处于全屏状态
     * 通过查找播放器控制栏来判断
     */
    private static boolean isPlayerFullscreen(Activity activity) {
        try {
            // 方法1：检查是否有播放器内部按钮（通过类名）
            android.view.ViewGroup decorView = (android.view.ViewGroup) activity.getWindow().getDecorView();
            if (hasPlayerWidget(decorView)) {
                LogUtils.getInstance().logDebug(TAG, "找到播放器内部按钮");
                return true;
            }
            
            // 方法2：检查屏幕方向
            int orientation = activity.getResources().getConfiguration().orientation;
            if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                // 横屏时可能是全屏
                LogUtils.getInstance().logDebug(TAG, "横屏模式，可能是全屏");
                return true;
            }
            
            // 方法3：检查DecorView中是否有播放器相关的视图
            if (hasPlayerView(decorView)) {
                LogUtils.getInstance().logDebug(TAG, "找到播放器视图");
                return true;
            }
            
            return false;
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "检查全屏状态失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查是否有播放器内部按钮（GeminiPlayerLikeWidget等）
     */
    private static boolean hasPlayerWidget(android.view.ViewGroup root) {
        String[] playerWidgetClasses = {
            "com.bilibili.app.gemini.player.widget.like.GeminiPlayerLikeWidget",
            "com.bilibili.app.gemini.player.widget.coin.GeminiPlayerCoinWidget",
            "com.bilibili.app.gemini.player.widget.share.GeminiPlayerShareIconWidget"
        };
        
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            String childClassName = child.getClass().getName();
            
            for (String widgetClass : playerWidgetClasses) {
                if (childClassName.equals(widgetClass)) {
                    return true;
                }
            }
            
            if (child instanceof android.view.ViewGroup) {
                if (hasPlayerWidget((android.view.ViewGroup) child)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * 检查是否有播放器视图
     */
    private static boolean hasPlayerView(android.view.ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            String className = child.getClass().getName().toLowerCase();
            
            // 检查是否是播放器相关视图
            if (className.contains("player") || className.contains("video") || 
                className.contains("danmaku") || className.contains("ijk") ||
                className.contains("exo") || className.contains("media")) {
                // 检查视图大小，全屏播放器通常占据大部分屏幕
                if (child.getWidth() > root.getWidth() * 0.8 && 
                    child.getHeight() > root.getHeight() * 0.5) {
                    return true;
                }
            }
            
            // 递归检查
            if (child instanceof android.view.ViewGroup) {
                if (hasPlayerView((android.view.ViewGroup) child)) {
                    return true;
                }
            }
        }
        return false;
    }
    
    
    /**
     * 创建 SponsorBlock 白色纯线条图标
     * 简洁的跳过/快进符号
     */
    private static android.graphics.Bitmap createSponsorBlockIcon(android.content.Context context) {
        int size = (int) (32 * context.getResources().getDisplayMetrics().density);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);

        // 白色线条
        int white = 0xFFFFFFFF;
        float centerY = size / 2f;
        float strokeWidth = size * 0.08f;

        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(white);
        paint.setAntiAlias(true);
        paint.setStyle(android.graphics.Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);

        // 绘制两个三角形组成的快进/跳过符号（纯线条）
        float triangleWidth = size * 0.25f;
        float triangleHeight = size * 0.5f;
        float startX = size * 0.2f;
        float offsetX = size * 0.15f;

        // 第一个三角形
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(startX, centerY - triangleHeight / 2);
        path.lineTo(startX + triangleWidth, centerY);
        path.lineTo(startX, centerY + triangleHeight / 2);
        path.close();

        // 第二个三角形（偏移）
        path.moveTo(startX + offsetX, centerY - triangleHeight / 2);
        path.lineTo(startX + offsetX + triangleWidth, centerY);
        path.lineTo(startX + offsetX, centerY + triangleHeight / 2);
        path.close();

        canvas.drawPath(path, paint);

        return bitmap;
    }
    
    /**
     * 创建提交按钮图标（旧版本兼容）
     */
    private static android.graphics.Bitmap createSubmitIcon(android.content.Context context, int color) {
        return createSponsorBlockIcon(context);
    }

    /**
     * 显示提交片段的 Dialog 弹窗（Material You + Monet 风格）
     */
    private static void showSubmitDialog(Activity activity) {
        try {
            // 获取视频信息
            VideoInfo info = getCurrentVideoInfo();
            String bvid = null;
            String cid = null;
            double currentTime = getCurrentPosition() / 1000.0;

            if (info != null && info.isComplete()) {
                bvid = info.getBvid();
                cid = info.getCid();
            } else if (lastKnownVideoInfo.isComplete()) {
                bvid = lastKnownVideoInfo.getBvid();
                cid = lastKnownVideoInfo.getCid();
            } else {
                VideoInfo currentInfo = currentVideoInfo.get();
                if (currentInfo != null) {
                    bvid = currentInfo.getBvid() != null ? currentInfo.getBvid() : lastKnownVideoInfo.getBvid();
                    cid = currentInfo.getCid() != null ? currentInfo.getCid() : lastKnownVideoInfo.getCid();
                }
            }

            if (bvid == null || bvid.isEmpty()) {
                android.widget.Toast.makeText(activity,
                    "无法获取视频BV号，请稍后再试", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            final String finalBvid = bvid;
            final String finalCid = cid;

            // 获取 Monet 颜色
            int primaryColor = MonetColorUtils.getMonetPrimaryColor(activity);
            int onSurfaceColor = MonetColorUtils.getMonetOnSurfaceColor(activity);
            int surfaceVariantColor = MonetColorUtils.getMonetSurfaceVariantColor(activity);
            int outlineColor = MonetColorUtils.getMonetOutlineColor(activity);

            // 创建 Dialog（Material You 风格）
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert);
            builder.setTitle("提交空降片段");

            // 创建自定义布局
            android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
            layout.setPadding(padding, padding, padding, padding);

            // 当前时间显示（Material 圆角卡片样式）
            android.widget.LinearLayout timeCard = new android.widget.LinearLayout(activity);
            timeCard.setOrientation(android.widget.LinearLayout.VERTICAL);
            // 创建圆角背景
            android.graphics.drawable.GradientDrawable cardBackground = new android.graphics.drawable.GradientDrawable();
            cardBackground.setColor(surfaceVariantColor);
            cardBackground.setCornerRadius(16 * activity.getResources().getDisplayMetrics().density);
            timeCard.setBackground(cardBackground);
            int cardPadding = (int) (12 * activity.getResources().getDisplayMetrics().density);
            timeCard.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
            android.widget.LinearLayout.LayoutParams cardParams = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(0, 0, 0, (int) (12 * activity.getResources().getDisplayMetrics().density));
            timeCard.setLayoutParams(cardParams);

            android.widget.TextView tvTimeLabel = new android.widget.TextView(activity);
            tvTimeLabel.setText("当前播放位置");
            tvTimeLabel.setTextSize(11);
            tvTimeLabel.setTextColor(outlineColor);
            timeCard.addView(tvTimeLabel);

            android.widget.TextView tvTime = new android.widget.TextView(activity);
            tvTime.setText(formatTime(currentTime));
            tvTime.setTextSize(20);
            tvTime.setTextColor(primaryColor);
            timeCard.addView(tvTime);

            layout.addView(timeCard);

            // 类别选择（Material 样式）
            android.widget.TextView tvCategory = new android.widget.TextView(activity);
            tvCategory.setText("片段类别");
            tvCategory.setTextSize(11);
            tvCategory.setTextColor(outlineColor);
            tvCategory.setPadding(0, (int) (4 * activity.getResources().getDisplayMetrics().density), 0,
                (int) (2 * activity.getResources().getDisplayMetrics().density));
            layout.addView(tvCategory);

            // 使用按钮代替Spinner，避免显示问题
            final String[] categories = {"sponsor", "selfpromo", "intro", "outro", "interaction", "preview", "filler", "music_offtopic"};
            final String[] categoryNames = {"赞助商广告", "自我推广", "片头", "片尾", "互动提醒", "预览/回顾", "填充内容", "非音乐部分"};
            final int[] selectedCategoryIndex = {0}; // 默认选择第一个

            android.widget.Button categoryButton = new android.widget.Button(activity);
            categoryButton.setText(categoryNames[0]);
            categoryButton.setTextSize(14);
            categoryButton.setTextColor(primaryColor);
            categoryButton.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            categoryButton.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);

            categoryButton.setOnClickListener(v -> {
                android.app.AlertDialog.Builder categoryBuilder = new android.app.AlertDialog.Builder(activity, android.R.style.Theme_Material_Light_Dialog_Alert);
                categoryBuilder.setTitle("选择片段类别");
                categoryBuilder.setItems(categoryNames, (dialog, which) -> {
                    selectedCategoryIndex[0] = which;
                    categoryButton.setText(categoryNames[which]);
                });
                android.app.AlertDialog categoryDialog = categoryBuilder.create();
                categoryDialog.show();
                // 限制对话框高度
                android.view.Window window = categoryDialog.getWindow();
                if (window != null) {
                    window.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.7),
                        (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.5));
                }
            });

            layout.addView(categoryButton);

            // 时间输入区域（水平布局）
            android.widget.LinearLayout timeLayout = new android.widget.LinearLayout(activity);
            timeLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            timeLayout.setPadding(0, (int) (12 * activity.getResources().getDisplayMetrics().density), 0, 0);

            // 开始时间
            android.widget.LinearLayout startLayout = new android.widget.LinearLayout(activity);
            startLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            startLayout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            android.widget.TextView tvStart = new android.widget.TextView(activity);
            tvStart.setText("开始时间");
            tvStart.setTextSize(11);
            tvStart.setTextColor(outlineColor);
            startLayout.addView(tvStart);

            android.widget.EditText etStart = new android.widget.EditText(activity);
            etStart.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etStart.setText(String.format("%.1f", currentTime));
            etStart.setTextSize(14);
            etStart.setTextColor(onSurfaceColor);
            etStart.setBackground(null);
            startLayout.addView(etStart);

            timeLayout.addView(startLayout);

            // 分隔符
            android.widget.TextView tvSeparator = new android.widget.TextView(activity);
            tvSeparator.setText("~");
            tvSeparator.setTextSize(16);
            tvSeparator.setTextColor(outlineColor);
            tvSeparator.setPadding((int) (12 * activity.getResources().getDisplayMetrics().density), 0,
                (int) (12 * activity.getResources().getDisplayMetrics().density), 0);
            timeLayout.addView(tvSeparator);

            // 结束时间
            android.widget.LinearLayout endLayout = new android.widget.LinearLayout(activity);
            endLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            endLayout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            android.widget.TextView tvEnd = new android.widget.TextView(activity);
            tvEnd.setText("结束时间");
            tvEnd.setTextSize(11);
            tvEnd.setTextColor(outlineColor);
            endLayout.addView(tvEnd);

            android.widget.EditText etEnd = new android.widget.EditText(activity);
            etEnd.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etEnd.setText(String.format("%.1f", currentTime + 5));
            etEnd.setTextSize(14);
            etEnd.setTextColor(onSurfaceColor);
            etEnd.setBackground(null);
            endLayout.addView(etEnd);

            timeLayout.addView(endLayout);
            layout.addView(timeLayout);

            builder.setView(layout);

            // 提交按钮（Monet 主色）
            builder.setPositiveButton("提交", (dialog, which) -> {
                try {
                    String category = categories[selectedCategoryIndex[0]];
                    double startTime = Double.parseDouble(etStart.getText().toString());
                    double endTime = Double.parseDouble(etEnd.getText().toString());

                    if (endTime <= startTime) {
                        android.widget.Toast.makeText(activity,
                            "结束时间必须大于开始时间", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }

                    submitSegment(finalBvid, finalCid, category, startTime, endTime, activity);
                } catch (Exception e) {
                    LogUtils.getInstance().logError(TAG, "提交片段失败", e);
                    android.widget.Toast.makeText(activity,
                        "提交失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                }
            });

            // 取消按钮
            builder.setNegativeButton("取消", null);

            // 打开设置按钮
            builder.setNeutralButton("设置", (dialog, which) -> {
                try {
                    Intent intent = new Intent();
                    intent.setClassName("com.example.bilibilisponsorblock",
                        "com.example.bilibilisponsorblock.MaterialSettingsActivity");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                } catch (Exception e) {
                    LogUtils.getInstance().logError(TAG, "打开设置失败", e);
                }
            });

            android.app.AlertDialog dialog = builder.create();
            dialog.show();

            // 设置按钮颜色（Monet 颜色）
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(primaryColor);
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(outlineColor);
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(outlineColor);

        } catch (Exception e) {
            LogUtils.getInstance().logError(TAG, "显示提交对话框失败", e);
            android.widget.Toast.makeText(activity,
                "显示对话框失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 提交片段到服务器
     */
    private static void submitSegment(String bvid, String cid, String category, double startTime, double endTime, Activity activity) {
        CompletableFuture.runAsync(() -> {
            try {
                boolean success = SponsorBlockAPI.submitSegment(bvid, cid, category, startTime, endTime);
                LogUtils.getInstance().log(TAG, "提交片段结果: " + (success ? "成功" : "失败"));

                mainHandler.post(() -> {
                    if (success) {
                        android.widget.Toast.makeText(activity,
                            "片段提交成功！", android.widget.Toast.LENGTH_SHORT).show();
                    } else {
                        android.widget.Toast.makeText(activity,
                            "片段提交失败，请稍后重试", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                LogUtils.getInstance().logError(TAG, "提交片段失败", e);
                mainHandler.post(() -> {
                    android.widget.Toast.makeText(activity,
                        "提交失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 格式化时间
     */
    private static String formatTime(double seconds) {
        int mins = (int) (seconds / 60);
        int secs = (int) (seconds % 60);
        return String.format("%d:%02d", mins, secs);
    }

    /**
     * 移除提交按钮
     */
    private static void removeSubmitButton(Activity activity) {
        android.view.View view = submitButtons.remove(activity);
        if (view != null) {
            try {
                android.view.ViewGroup parent = (android.view.ViewGroup) view.getParent();
                if (parent != null) {
                    parent.removeView(view);
                }
            } catch (Exception e) {
                // 忽略
            }
        }
    }

    /**
     * 判断是否是视频相关Activity
     */
    private static boolean isVideoActivity(String className) {
        String[] videoPatterns = {
            "Player",
            "Video",
            "Play",
            "Detail",
            "UnitedPlaylist",
            "MainActivityV2"
        };

        for (String pattern : videoPatterns) {
            if (className.contains(pattern)) {
                return true;
            }
        }

        return className.contains("bili") || className.contains("danmaku");
    }

    /**
     * 判断是否是视频详情页（只在视频详情页显示提交按钮）
     */
    private static boolean isVideoDetailActivity(String className) {
        // 排除首页/主页面
        if (className.contains("MainActivityV2") || 
            className.equals("tv.danmaku.bili.MainActivityV2") ||
            className.contains("HomeActivity") ||
            className.contains("MainActivity")) {
            return false;
        }
        
        // 只在具体的视频详情页显示按钮
        String[] videoDetailPatterns = {
            "UnitedBizDetailsActivity",  // 视频详情页
            "UnitedPlaylistActivity",     // 播放列表页
            "VideoDetailsActivity",       // 视频详情（旧版）
            "PlayerActivity",             // 播放器页面
            "BiliPlayerActivity",         // Bili播放器
            "tv.danmaku.bili.ui.video.VideoDetailsActivity",
            "tv.danmaku.bili.ui.player.PlayerActivity"
        };

        for (String pattern : videoDetailPatterns) {
            if (className.contains(pattern)) {
                return true;
            }
        }

        // 如果是 Bilibili 的 Activity 且包含视频相关关键词（排除主页相关）
        if ((className.contains("bili") || className.contains("danmaku")) &&
            (className.contains("Video") || className.contains("Player") || 
             className.contains("Play") || className.contains("Detail")) &&
            !className.contains("Home") && !className.contains("Main")) {
            return true;
        }

        return false;
    }

    /**
     * 从Intent提取视频信息（增强版）
     * 支持多种方式获取BV号和CID
     */
    private static void extractVideoInfoFromIntent(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) return;

            String bvid = null;
            String cid = null;

            // 1. 从URI解析
            Uri data = intent.getData();
            if (data != null) {
                String path = data.getPath();
                String uriString = data.toString();
                
                // 1.1 从路径解析 /video/BVxxx
                if (path != null && path.contains("/video/")) {
                    int start = path.indexOf("/video/") + 7;
                    int end = path.indexOf("/", start);
                    if (end == -1) end = path.length();
                    String possibleBvid = path.substring(start, end);
                    if (possibleBvid.startsWith("BV")) {
                        bvid = possibleBvid;
                        LogUtils.getInstance().log(TAG, "从URI路径获取 BV: " + bvid);
                    }
                }
                
                // 1.2 从完整URL解析BV号（支持b23.tv短链接）
                if (bvid == null) {
                    bvid = extractBvidFromUrl(uriString);
                    if (bvid != null) {
                        LogUtils.getInstance().log(TAG, "从URL解析 BV: " + bvid);
                    }
                }
                
                // 1.3 从query参数获取
                String cidParam = data.getQueryParameter("cid");
                if (cidParam != null && !cidParam.isEmpty()) {
                    cid = cidParam;
                    LogUtils.getInstance().log(TAG, "从URI参数获取 CID: " + cid);
                }
                String bvidParam = data.getQueryParameter("bvid");
                if (bvidParam != null && !bvidParam.isEmpty()) {
                    bvid = bvidParam;
                    LogUtils.getInstance().log(TAG, "从URI参数获取 BV: " + bvid);
                }
                
                // 1.4 从aid参数获取并转换
                if (bvid == null) {
                    String aidParam = data.getQueryParameter("aid");
                    if (aidParam != null && !aidParam.isEmpty()) {
                        try {
                            long aid = Long.parseLong(aidParam);
                            String convertedBvid = BvidConverter.aidToBvid(aid);
                            LogUtils.getInstance().log(TAG, "从aid转换 BV: aid=" + aid + ", 转换结果='" + convertedBvid + "', 长度=" + (convertedBvid != null ? convertedBvid.length() : 0));
                            if (convertedBvid != null && !convertedBvid.isEmpty() && convertedBvid.startsWith("BV")) {
                                bvid = convertedBvid;
                                LogUtils.getInstance().log(TAG, "aid转换成功: " + aid + " -> " + bvid);
                            } else {
                                LogUtils.getInstance().log(TAG, "aid转换失败，结果无效: '" + convertedBvid + "'");
                            }
                        } catch (NumberFormatException e) {
                            LogUtils.getInstance().logDebug(TAG, "aid格式错误: " + aidParam);
                        }
                    }
                }
            }

            // 2. 从extras获取（只有在还没有获取到bvid时才尝试）
            if (bvid == null) {
                Bundle extras = intent.getExtras();
                if (extras != null) {
                    // 2.1 获取bvid
                    Object bvidObj = extras.get("bvid");
                    if (bvidObj instanceof String) {
                        String bvidFromExtra = (String) bvidObj;
                        if (bvidFromExtra != null && !bvidFromExtra.isEmpty() && bvidFromExtra.startsWith("BV")) {
                            bvid = bvidFromExtra;
                            LogUtils.getInstance().log(TAG, "从Extras获取 BV: " + bvid);
                        }
                    }

                    // 2.2 获取cid（支持多种类型）
                    Object cidObj = extras.get("cid");
                    if (cidObj instanceof String) {
                        cid = (String) cidObj;
                    } else if (cidObj instanceof Long) {
                        cid = String.valueOf((Long) cidObj);
                    } else if (cidObj instanceof Integer) {
                        cid = String.valueOf((Integer) cidObj);
                    }
                    if (cid != null) {
                        LogUtils.getInstance().log(TAG, "从Extras获取 CID: " + cid);
                    }

                    // 2.3 尝试从其他可能的key获取
                    if (bvid == null) {
                        String[] bvidKeys = {"BV", "bv", "bvid", "BVID", "videoId", "video_id"};
                        for (String key : bvidKeys) {
                            Object obj = extras.get(key);
                            if (obj instanceof String) {
                                String val = (String) obj;
                                if (val.startsWith("BV")) {
                                    bvid = val;
                                    LogUtils.getInstance().log(TAG, "从Extras[" + key + "]获取 BV: " + bvid);
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            // 3. 更新视频信息
            LogUtils.getInstance().log(TAG, "准备更新视频信息: bvid=" + bvid + ", cid=" + cid);
            if (bvid != null && !bvid.isEmpty()) {
                if (bvid.startsWith("BV")) {
                    updateLastKnownBvid(bvid);
                } else {
                    LogUtils.getInstance().log(TAG, "BV格式错误: " + bvid);
                }
            } else {
                LogUtils.getInstance().log(TAG, "bvid为空，跳过更新");
            }
            if (cid != null && !cid.isEmpty()) {
                updateLastKnownCid(cid);
            }
            
            // 4. 如果获取到BV但没有CID，尝试从当前播放器状态获取
            if (bvid != null && (cid == null || cid.isEmpty())) {
                PlayerState state = activeState.get();
                if (state != null && state.videoInfo.getCid() != null) {
                    LogUtils.getInstance().log(TAG, "使用播放器状态的CID: " + state.videoInfo.getCid());
                }
            }

        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "从Intent提取失败: " + e.getMessage());
        }
    }

    /**
     * 从URL字符串中提取BV号
     * 支持多种URL格式：b23.tv、www.bilibili.com/video/、bilibili.com/video/等
     */
    private static String extractBvidFromUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        
        try {
            // 匹配BV号模式：BV + 10个字母数字
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("BV[a-zA-Z0-9]{10}");
            java.util.regex.Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                return matcher.group();
            }
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "从URL提取BV失败: " + e.getMessage());
        }
        return null;
    }

    private static PlayerState getOrCreateState(Object player) {
        PlayerState state = playerStates.get(player);
        if (state == null) {
            state = new PlayerState(player);
            playerStates.put(player, state);
        }
        return state;
    }

    private static void updateLastKnownBvid(String bvid) {
        if (bvid != null && !bvid.isEmpty() && bvid.startsWith("BV")) {
            String oldBvid = lastKnownVideoInfo.getBvid();
            // 如果BV变化了，清除旧的CID避免混用
            if (!bvid.equals(oldBvid)) {
                lastKnownVideoInfo.setCid(null);
                LogUtils.getInstance().log(TAG, "BV变化: " + oldBvid + " -> " + bvid);
            }
            lastKnownVideoInfo.setBvid(bvid);
            LogUtils.getInstance().log(TAG, "更新 BV: " + bvid + ", CID=" + lastKnownVideoInfo.getCid());
        }
    }

    private static void updateLastKnownCid(String cid) {
        if (cid != null && !cid.isEmpty() && !cid.equals("0")) {
            lastKnownVideoInfo.setCid(cid);
            LogUtils.getInstance().log(TAG, "更新 CID: " + cid);
        }
    }

    /**
     * 清理已知的视频信息
     * 在切换视频时调用，避免新旧视频信息混用
     */
    private static void clearLastKnownVideoInfo() {
        String oldBvid = lastKnownVideoInfo.getBvid();
        String oldCid = lastKnownVideoInfo.getCid();
        
        if (oldBvid != null || oldCid != null) {
            lastKnownVideoInfo.setBvid(null);
            lastKnownVideoInfo.setCid(null);
            LogUtils.getInstance().log(TAG, "清理旧视频信息: BV=" + oldBvid + ", CID=" + oldCid);
        }
    }
    
    private static void extractCidFromPlayUrl(String url) {
        try {
            if (url == null) return;

            LogUtils.getInstance().logDebug(TAG, "extractCidFromPlayUrl: " + url.substring(0, Math.min(200, url.length())));

            Uri uri = Uri.parse(url);
            String cid = null;
            String bvid = null;

            // 方法1：从 URL 参数获取 bvid
            String bvidParam = uri.getQueryParameter("bvid");
            if (bvidParam != null && !bvidParam.isEmpty()) {
                bvid = bvidParam;
                LogUtils.getInstance().log(TAG, "从播放URL获取 BV: " + bvid);
            }

            // 方法2：从 URL 参数获取 aid 并转换
            if (bvid == null) {
                String aidParam = uri.getQueryParameter("aid");
                if (aidParam != null && !aidParam.isEmpty()) {
                    try {
                        long aid = Long.parseLong(aidParam);
                        bvid = BvidConverter.aidToBvid(aid);
                        LogUtils.getInstance().log(TAG, "从播放URL aid 转换 BV: " + aid + " -> " + bvid);
                    } catch (NumberFormatException e) {
                        LogUtils.getInstance().logDebug(TAG, "aid格式错误: " + aidParam);
                    }
                }
            }

            // 更新 BV（优先使用网络请求的 BV，因为这是实际播放的视频）
            if (bvid != null && !bvid.isEmpty() && bvid.startsWith("BV")) {
                String currentBvid = lastKnownVideoInfo.getBvid();
                if (!bvid.equals(currentBvid)) {
                    // 网络请求的 BV 与当前 BV 不同，优先使用网络请求的 BV
                    LogUtils.getInstance().log(TAG, "网络请求 BV 与当前不同，优先使用网络请求: " + currentBvid + " -> " + bvid);
                    lastKnownVideoInfo.setBvid(bvid);
                    // 清除旧的 CID，等待新的 CID
                    lastKnownVideoInfo.setCid(null);
                }
            }

            // 方法3：从 URL 参数获取 cid
            cid = uri.getQueryParameter("cid");

            // 方法4：从路径中提取
            if (cid == null || cid.isEmpty() || cid.equals("0")) {
                String path = uri.getPath();
                if (path != null) {
                    // 尝试匹配 /cid/123456 格式
                    String[] parts = path.split("/");
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].equalsIgnoreCase("cid") && i + 1 < parts.length) {
                            cid = parts[i + 1];
                            break;
                        }
                    }
                }
            }

            // 方法5：从 fragment 中获取
            if (cid == null || cid.isEmpty() || cid.equals("0")) {
                String fragment = uri.getFragment();
                if (fragment != null && fragment.contains("cid=")) {
                    int start = fragment.indexOf("cid=") + 4;
                    int end = fragment.indexOf("&", start);
                    if (end == -1) end = fragment.length();
                    cid = fragment.substring(start, end);
                }
            }

            if (cid != null && !cid.isEmpty() && !cid.equals("0")) {
                updateLastKnownCid(cid);

                // 尝试加载片段
                if (lastKnownVideoInfo.isComplete()) {
                    VideoInfo info = new VideoInfo();
                    info.setBvid(lastKnownVideoInfo.getBvid());
                    info.setCid(cid);
                    currentVideoInfo.set(info);

                    PlayerState state = activeState.get();
                    if (state != null) {
                        state.videoInfo.setCid(cid);
                        if (state.videoInfo.getBvid() == null) {
                            state.videoInfo.setBvid(lastKnownVideoInfo.getBvid());
                        }
                        loadSegmentsIfReady(state);
                    } else {
                        // 如果没有活跃的播放器状态，直接加载片段
                        loadSegmentsWithInfo(lastKnownVideoInfo.getBvid(), cid);
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.getInstance().logError(TAG, "从播放URL提取CID失败", e);
        }
    }

    /**
     * 使用指定的 BV 和 CID 加载片段
     */
    private static void loadSegmentsWithInfo(String bvid, String cid) {
        // 检查是否启用测试模式（优先检查，不需要BV和CID）
        if (Preferences.isTestMode()) {
            LogUtils.getInstance().log(TAG, "测试模式已启用，使用测试片段");
            loadTestSegments();
            return;
        }
        
        if (bvid == null || cid == null) return;

        LogUtils.getInstance().log(TAG, "直接加载片段: " + bvid + "+" + cid);

        CompletableFuture.supplyAsync(() -> SponsorBlockAPI.getSegments(bvid, cid))
            .thenAccept(segments -> {
                // 更新全局 currentSegments
                currentSegments.clear();
                currentSegments.addAll(segments);

                LogUtils.getInstance().log(TAG,
                    String.format("视频 %s+%s 加载了 %d 个片段", bvid, cid, segments.size()));

                // 显示 Toast 提示
                if (segments.size() > 0) {
                    showToast("检测到 " + segments.size() + " 个空降片段");
                }

                for (int i = 0; i < segments.size(); i++) {
                    Segment seg = segments.get(i);
                    LogUtils.getInstance().log(TAG, String.format(
                        "片段[%d]: 类别=%s, 时间=%.2fs-%.2fs, UUID=%s",
                        i, seg.category, seg.segment[0], seg.segment[1], seg.uuid));
                }
            })
            .exceptionally(throwable -> {
                LogUtils.getInstance().logError(TAG, "加载片段失败", throwable);
                return null;
            });
    }

    /**
     * 加载测试片段（用于验证进度条颜色显示）
     */
    private static void loadTestSegments() {
        LogUtils.getInstance().log(TAG, "加载测试片段");

        // 创建测试片段 - 在视频的不同位置显示不同类别
        List<Segment> testSegments = new ArrayList<>();

        // 赞助商广告 - 红色 (10-15秒)
        Segment sponsor = new Segment();
        sponsor.category = "sponsor";
        sponsor.segment = new double[]{10.0, 15.0};
        sponsor.uuid = "test-sponsor-001";
        testSegments.add(sponsor);

        // 自我推广 - 橙色 (30-35秒)
        Segment selfpromo = new Segment();
        selfpromo.category = "selfpromo";
        selfpromo.segment = new double[]{30.0, 35.0};
        selfpromo.uuid = "test-selfpromo-001";
        testSegments.add(selfpromo);

        // 片头 - 绿色 (0-5秒)
        Segment intro = new Segment();
        intro.category = "intro";
        intro.segment = new double[]{0.0, 5.0};
        intro.uuid = "test-intro-001";
        testSegments.add(intro);

        // 片尾 - 蓝色 (视频最后10秒，假设视频60秒)
        Segment outro = new Segment();
        outro.category = "outro";
        outro.segment = new double[]{50.0, 60.0};
        outro.uuid = "test-outro-001";
        testSegments.add(outro);

        // 互动提醒 - 紫色 (20-22秒)
        Segment interaction = new Segment();
        interaction.category = "interaction";
        interaction.segment = new double[]{20.0, 22.0};
        interaction.uuid = "test-interaction-001";
        testSegments.add(interaction);

        // 更新全局 currentSegments
        currentSegments.clear();
        currentSegments.addAll(testSegments);

        LogUtils.getInstance().log(TAG, "测试模式：加载了 " + testSegments.size() + " 个测试片段");
        showToast("测试模式：显示 " + testSegments.size() + " 个测试片段");

        for (int i = 0; i < testSegments.size(); i++) {
            Segment seg = testSegments.get(i);
            LogUtils.getInstance().log(TAG, String.format(
                "测试片段[%d]: 类别=%s, 时间=%.2fs-%.2fs",
                i, seg.category, seg.segment[0], seg.segment[1]));
        }
    }

    private static void loadSegmentsIfReady(PlayerState state) {
        if (state.videoInfo.isComplete()) {
            loadSegments(state);
        } else {
            LogUtils.getInstance().logDebug(TAG, "视频信息不完整，等待: " + state.videoInfo);
        }
    }

    private static void loadSegments(PlayerState state) {
        String bvid = state.videoInfo.getBvid();
        String cid = state.videoInfo.getCid();

        LogUtils.getInstance().log(TAG, "开始加载片段: " + bvid + "+" + cid);

        // 检查是否启用测试模式
        if (Preferences.isTestMode()) {
            LogUtils.getInstance().log(TAG, "测试模式已启用，使用测试片段");
            loadTestSegments();
            return;
        }

        CompletableFuture.supplyAsync(() -> SponsorBlockAPI.getSegments(bvid, cid))
            .thenAccept(segments -> {
                // 更新 state 中的片段
                state.segments.clear();
                state.segments.addAll(segments);
                state.skippedSegments.clear();

                // 同时更新全局 currentSegments（用于 ProgressBarHook）
                currentSegments.clear();
                currentSegments.addAll(segments);

                // 添加详细日志
                LogUtils.getInstance().log(TAG,
                    String.format("视频 %s+%s 加载了 %d 个片段", bvid, cid, segments.size()));

                // 显示 Toast 提示
                if (segments.size() > 0) {
                    showToast("检测到 " + segments.size() + " 个空降片段");
                }

                // 打印每个片段的详细信息
                for (int i = 0; i < segments.size(); i++) {
                    Segment seg = segments.get(i);
                    LogUtils.getInstance().log(TAG, String.format(
                        "片段[%d]: 类别=%s, 时间=%.2fs-%.2fs, UUID=%s",
                        i, seg.category, seg.segment[0], seg.segment[1], seg.uuid));
                }
            })
            .exceptionally(throwable -> {
                LogUtils.getInstance().logError(TAG, "加载片段失败", throwable);
                return null;
            });
    }

    /**
     * 显示 Toast 提示（防止重复显示）
     */
    private static void showToast(String message) {
        mainHandler.post(() -> {
            try {
                // 获取当前视频key
                String currentVideoKey = lastKnownVideoInfo.getBvid() + "_" + lastKnownVideoInfo.getCid();
                String toastKey = currentVideoKey + "_" + message;
                
                // 检查是否已经显示过相同的 Toast
                if (toastKey.equals(lastToastVideoKey)) {
                    return;
                }
                lastToastVideoKey = toastKey;
                
                // 获取当前 Activity
                android.app.Activity currentActivity = getCurrentActivity();
                if (currentActivity != null) {
                    android.widget.Toast.makeText(currentActivity, message, android.widget.Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                LogUtils.getInstance().logDebug(TAG, "显示 Toast 失败: " + e.getMessage());
            }
        });
    }

    /**
     * 获取当前 Activity
     */
    private static android.app.Activity getCurrentActivity() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Map<?, ?> activities = (Map<?, ?>) activitiesField.get(activityThread);
            
            for (Object activityRecord : activities.values()) {
                Class<?> activityRecordClass = activityRecord.getClass();
                Field pausedField = activityRecordClass.getDeclaredField("paused");
                pausedField.setAccessible(true);
                boolean paused = pausedField.getBoolean(activityRecord);
                if (!paused) {
                    Field activityField = activityRecordClass.getDeclaredField("activity");
                    activityField.setAccessible(true);
                    return (android.app.Activity) activityField.get(activityRecord);
                }
            }
        } catch (Exception e) {
            LogUtils.getInstance().logDebug(TAG, "获取当前 Activity 失败: " + e.getMessage());
        }
        return null;
    }

    private static void startPeriodicCheck() {
        if (checkTask != null) {
            checkTask.cancel(false);
        }

        checkTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                checkAndSkip();
            } catch (Exception e) {
                LogUtils.getInstance().logError(TAG, "检查任务异常", e);
            }
        }, CHECK_INTERVAL, CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private static void checkAndSkip() {
        if (!Preferences.isModuleEnabled()) return;

        PlayerState state = activeState.get();
        if (state == null || !state.isActive || state.isPaused) return;

        Object player = activePlayer.get();
        if (player == null) return;

        // 使用全局 currentSegments 而不是 state.segments
        List<Segment> segmentsToCheck = currentSegments.isEmpty() ? state.segments : currentSegments;
        if (segmentsToCheck.isEmpty()) return;

        long currentTimeMs = state.currentPosition;
        double currentTimeSec = currentTimeMs / 1000.0;

        Set<String> enabledCategories = Preferences.getSkipCategories();

        // 添加调试日志
        LogUtils.getInstance().logDebug(TAG,
            String.format("检查跳过: 当前时间=%.2fs, 片段数=%d", currentTimeSec, segmentsToCheck.size()));

        for (Segment segment : segmentsToCheck) {
            if (!enabledCategories.contains(segment.category)) {
                LogUtils.getInstance().logDebug(TAG, "类别未启用: " + segment.category);
                continue;
            }

            double segmentDuration = segment.segment[1] - segment.segment[0];
            if (segmentDuration < MIN_SEGMENT_DURATION) continue;

            double startTime = segment.segment[0];
            double endTime = segment.segment[1];

            boolean shouldSkip = currentTimeSec >= startTime - (PRE_SKIP_THRESHOLD / 1000.0) &&
                currentTimeSec < endTime;

            // 添加调试日志
            LogUtils.getInstance().logDebug(TAG, String.format(
                "检查片段: 类别=%s, 时间=%.2fs-%.2fs, 当前=%.2fs, 是否匹配=%b",
                segment.category, startTime, endTime, currentTimeSec, shouldSkip));

            if (shouldSkip) {
                long now = System.currentTimeMillis();
                if (now - lastSkipTime.get() < SKIP_COOLDOWN) continue;

                // 获取该片段类别的跳过模式
                SkipMode mode = Preferences.getSkipMode(segment.category);

                LogUtils.getInstance().logDebug(TAG, "跳过模式: " + mode.getDisplayName());

                switch (mode) {
                    case NEVER:
                        // 不跳过，直接继续
                        continue;

                    case MANUAL:
                        // 手动跳过模式：显示提示，等待用户手动操作
                        // 只提示一次
                        if (!state.skippedSegments.contains(segment.uuid + "_manual_shown")) {
                            showManualSkipNotification(segment, endTime);
                            state.skippedSegments.add(segment.uuid + "_manual_shown");
                        }
                        continue;

                    case ONCE:
                        // 仅跳过一次模式：检查是否已跳过
                        if (state.skippedSegments.contains(segment.uuid + "_once")) {
                            continue;
                        }
                        executeSkip(state, segment, endTime);
                        state.skippedSegments.add(segment.uuid + "_once");
                        break;

                    case ALWAYS:
                    default:
                        // 总是跳过模式（默认行为）
                        if (state.skippedSegments.contains(segment.uuid)) continue;
                        executeSkip(state, segment, endTime);
                        break;
                }
                break;
            }
        }
    }

    /**
     * 显示手动跳过通知
     */
    private static void showManualSkipNotification(Segment segment, double endTimeSec) {
        mainHandler.post(() -> {
            try {
                String msg = "即将跳过" + getCategoryName(segment.category) + 
                    " (" + String.format("%.1f", segment.segment[0]) + "s - " +
                    String.format("%.1f", endTimeSec) + "s)，点击确认跳过";

                Object app = XposedHelpers.callMethod(
                    XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityThread"),
                        "currentApplication"
                    ), "getApplicationContext"
                );

                Class<?> toastClass = Class.forName("android.widget.Toast");
                Object toast = XposedHelpers.callStaticMethod(toastClass, "makeText",
                    app, msg, 1); // 长时间显示
                XposedHelpers.callMethod(toast, "show");

                LogUtils.getInstance().log(TAG, "手动跳过提示: " + getCategoryName(segment.category));

            } catch (Exception e) {
                LogUtils.getInstance().logDebug(TAG, "显示手动跳过通知失败: " + e.getMessage());
            }
        });
    }

    private static void executeSkip(PlayerState state, Segment segment, double endTimeSec) {
        final long skipToMs = (long)(endTimeSec * 1000);

        mainHandler.post(() -> {
            try {
                Object player = state.player;
                if (player == null) return;

                Method seekMethod = player.getClass().getMethod("seekTo", long.class);
                seekMethod.invoke(player, skipToMs);

                state.skippedSegments.add(segment.uuid);
                lastSkipTime.set(System.currentTimeMillis());

                LogUtils.getInstance().log(TAG,
                    String.format("已跳过 %s: %.2fs -> %.2fs",
                        getCategoryName(segment.category),
                        segment.segment[0], endTimeSec));

                if (Preferences.showToast()) {
                    showSkipToast(segment.category);
                }

            } catch (Exception e) {
                LogUtils.getInstance().logError(TAG, "跳过执行失败", e);
            }
        });
    }

    private static void showSkipToast(String category) {
        try {
            String msg = "已跳过" + getCategoryName(category);
            Object app = XposedHelpers.callMethod(
                XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityThread"),
                    "currentApplication"
                ), "getApplicationContext"
            );

            Class<?> toastClass = Class.forName("android.widget.Toast");
            Object toast = XposedHelpers.callStaticMethod(toastClass, "makeText",
                app, msg, 0);
            XposedHelpers.callMethod(toast, "show");
        } catch (Exception e) {
            // Toast失败不影响功能
        }
    }

    private static String getCategoryName(String category) {
        if ("sponsor".equals(category)) return "赞助商广告";
        if ("selfpromo".equals(category)) return "自我推广";
        if ("intro".equals(category)) return "片头";
        if ("outro".equals(category)) return "片尾";
        if ("interaction".equals(category)) return "互动提醒";
        if ("preview".equals(category)) return "预览/回顾";
        if ("filler".equals(category)) return "填充内容";
        return "片段";
    }

    private static void cleanupPlayer(Object player) {
        PlayerState state = playerStates.remove(player);
        if (state != null) {
            state.reset();
        }

        if (activePlayer.get() == player) {
            activePlayer.set(null);
            activeState.set(null);
        }

        LogUtils.getInstance().logDebug(TAG, "播放器已清理");
    }

    public static void stop() {
        if (checkTask != null) {
            checkTask.cancel(false);
            checkTask = null;
        }
        playerStates.clear();
        activePlayer.set(null);
        activeState.set(null);
    }

    /**
     * 获取当前播放位置（毫秒）
     * 供外部调用（如 SubmitSegmentActivity）
     */
    public static long getCurrentPosition() {
        PlayerState state = activeState.get();
        if (state != null) {
            return state.currentPosition;
        }
        return 0;
    }

    /**
     * 获取当前视频信息
     * 供外部调用
     */
    public static VideoInfo getCurrentVideoInfo() {
        // 优先返回 currentVideoInfo
        VideoInfo info = currentVideoInfo.get();
        if (info != null && info.isComplete()) {
            return info;
        }

        // 然后检查 activeState
        PlayerState state = activeState.get();
        if (state != null && state.videoInfo.isComplete()) {
            return state.videoInfo;
        }

        // 返回最后已知的视频信息
        if (lastKnownVideoInfo.isComplete()) {
            return lastKnownVideoInfo;
        }
        return null;
    }

    /**
     * 获取当前视频的所有片段
     */
    public static List<Segment> getCurrentSegments() {
        // 优先返回全局 currentSegments
        if (!currentSegments.isEmpty()) {
            return new ArrayList<>(currentSegments);
        }
        // 如果全局为空，尝试从 activeState 获取
        PlayerState state = activeState.get();
        if (state != null) {
            return new ArrayList<>(state.segments);
        }
        return new ArrayList<>();
    }

    /**
     * 获取当前视频总时长（毫秒）
     */
    public static long getVideoDuration() {
        PlayerState state = activeState.get();
        if (state != null && state.player != null) {
            try {
                Method getDurationMethod = state.player.getClass().getMethod("getDuration");
                Long duration = (Long) getDurationMethod.invoke(state.player);
                return duration != null ? duration : 0;
            } catch (Exception e) {
                LogUtils.getInstance().logDebug(TAG, "获取视频时长失败: " + e.getMessage());
            }
        }
        return 0;
    }
}