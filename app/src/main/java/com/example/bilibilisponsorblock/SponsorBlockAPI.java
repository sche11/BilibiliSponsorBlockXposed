package com.example.bilibilisponsorblock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 空降助手 (Bilibili SponsorBlock) API 接口
 * 基于 BiliPai 和 BilibiliSponsorBlock 官方实现
 * 
 * API 文档: https://github.com/hanydd/BilibiliSponsorBlock/wiki/API
 */
public class SponsorBlockAPI {

    // 空降助手 API 服务器 (来自 BiliPai 项目)
    private static final String BASE_URL = "https://bsbsb.top/api";
    
    // 备用服务器
    private static final String BACKUP_URL = "https://api.bilibili.sb";

    // 缓存相关
    private static final long CACHE_EXPIRE_TIME = TimeUnit.MINUTES.toMillis(5);
    private static final android.util.LruCache<String, CacheEntry> segmentCache =
            new android.util.LruCache<>(50);

    private static class CacheEntry {
        final List<Segment> segments;
        final long timestamp;

        CacheEntry(List<Segment> segments) {
            this.segments = segments;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRE_TIME;
        }
    }

    /**
     * 获取视频的空降片段
     * API: GET /api/skipSegments?videoID={bvid}&category=sponsor&category=intro...
     * 
     * @param bvid BV号 (如 BV1xx411c7mD)
     * @param cid  分P ID (可选)
     * @return 片段列表
     */
    public static List<Segment> getSegments(String bvid, String cid) {
        // 检查缓存
        String cacheKey = buildCacheKey(bvid, cid);
        CacheEntry cached = segmentCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            LogUtils.getInstance().logDebug("SponsorBlockAPI", "使用缓存数据: " + bvid);
            return cached.segments;
        }

        List<Segment> segments = new ArrayList<>();

        try {
            // 构建 videoID - 先只用 BV 号查询
            // bsbsb.top API 返回的数据包含 cid 字段，我们在本地筛选
            String videoID = bvid;

            String encodedID = URLEncoder.encode(videoID, StandardCharsets.UTF_8.name());

            // 构建类别参数
            List<String> categories = getEnabledCategories();
            StringBuilder categoryParams = new StringBuilder();
            for (String category : categories) {
                if (categoryParams.length() > 0) {
                    categoryParams.append("&");
                }
                categoryParams.append("category=").append(URLEncoder.encode(category, StandardCharsets.UTF_8.name()));
            }

            String urlString = BASE_URL + "/skipSegments?videoID=" + encodedID;
            if (categoryParams.length() > 0) {
                urlString += "&" + categoryParams.toString();
            }

            LogUtils.getInstance().log("SponsorBlockAPI", "请求: " + urlString);

            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "BilibiliSponsorBlockXposed/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // 解析片段并按 CID 筛选
                segments = parseSegments(response.toString(), cid);

                // 存入缓存
                segmentCache.put(cacheKey, new CacheEntry(segments));

                LogUtils.getInstance().log("SponsorBlockAPI",
                    "获取到 " + segments.size() + " 个空降片段 for " + bvid + " (CID: " + cid + ")");

            } else if (responseCode == 404) {
                // 没有空降数据，这是正常情况
                segmentCache.put(cacheKey, new CacheEntry(segments));
                LogUtils.getInstance().logDebug("SponsorBlockAPI",
                    "视频 " + videoID + " 没有空降数据");
            } else {
                LogUtils.getInstance().logError("SponsorBlockAPI",
                    "API 返回错误: " + responseCode);
            }

            connection.disconnect();

        } catch (Exception e) {
            LogUtils.getInstance().logError("SponsorBlockAPI", "获取空降片段失败", e);
        }

        return segments;
    }

    /**
     * 解析片段数据
     * BilibiliSponsorBlock API 返回格式:
     * [
     *   {
     *     "UUID": "片段唯一标识",
     *     "segment": [开始时间(秒), 结束时间(秒)],
     *     "category": "类别",
     *     "actionType": "动作类型",
     *     "cid": "分P ID"
     *   }
     * ]
     */
    private static List<Segment> parseSegments(String jsonResponse) {
        return parseSegments(jsonResponse, null);
    }
    
    /**
     * 解析片段数据并按 CID 筛选
     */
    private static List<Segment> parseSegments(String jsonResponse, String targetCid) {
        List<Segment> segments = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(jsonResponse);

            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                
                // 如果有 CID 字段，检查是否匹配
                String segmentCid = obj.optString("cid", "");
                if (targetCid != null && !targetCid.isEmpty() && !targetCid.equals("0")) {
                    // 如果片段有 CID 字段且与目标 CID 不匹配，跳过
                    if (!segmentCid.isEmpty() && !segmentCid.equals(targetCid)) {
                        continue;
                    }
                }

                Segment segment = new Segment();
                segment.uuid = obj.optString("UUID", "");
                segment.category = obj.optString("category", "sponsor");

                JSONArray segmentArray = obj.getJSONArray("segment");
                segment.segment = new double[]{
                    segmentArray.getDouble(0),
                    segmentArray.getDouble(1)
                };

                segment.actionType = obj.optString("actionType", "skip");

                // 只添加跳过类型的片段
                if ("skip".equals(segment.actionType)) {
                    segments.add(segment);
                }
            }

            // 按开始时间排序
            segments.sort((a, b) -> Double.compare(a.segment[0], b.segment[0]));

        } catch (Exception e) {
            LogUtils.getInstance().logError("SponsorBlockAPI", "解析片段失败", e);
        }

        return segments;
    }

    /**
     * 构建缓存键
     */
    private static String buildCacheKey(String bvid, String cid) {
        if (cid != null && !cid.isEmpty() && !cid.equals("0")) {
            String key = bvid + "+" + cid;
            LogUtils.getInstance().logDebug("SponsorBlockAPI", "缓存键: " + key);
            return key;
        }
        LogUtils.getInstance().logDebug("SponsorBlockAPI", "缓存键: " + bvid);
        return bvid;
    }

    /**
     * 获取启用的类别列表
     */
    private static List<String> getEnabledCategories() {
        List<String> categories = new ArrayList<>();

        if (Preferences.shouldSkipSponsor()) categories.add("sponsor");
        if (Preferences.shouldSkipSelfPromo()) categories.add("selfpromo");
        if (Preferences.shouldSkipIntro()) categories.add("intro");
        if (Preferences.shouldSkipOutro()) categories.add("outro");
        if (Preferences.shouldSkipInteraction()) categories.add("interaction");
        if (Preferences.shouldSkipPreview()) categories.add("preview");
        if (Preferences.shouldSkipFiller()) categories.add("filler");
        if (Preferences.shouldSkipMusicOfftopic()) categories.add("music_offtopic");

        return categories;
    }

    /**
     * 清除缓存
     */
    public static void clearCache() {
        segmentCache.evictAll();
        LogUtils.getInstance().log("SponsorBlockAPI", "缓存已清除");
    }

    /**
     * 检查当前播放位置是否在某个空降片段内
     * @param segments 片段列表
     * @param currentPositionMs 当前播放位置（毫秒）
     * @return 匹配的片段，没有则返回 null
     */
    public static Segment findSegmentAtPosition(List<Segment> segments, long currentPositionMs) {
        double currentSeconds = currentPositionMs / 1000.0;
        for (Segment segment : segments) {
            if (currentSeconds >= segment.segment[0] && currentSeconds < segment.segment[1] - 0.5) {
                return segment;
            }
        }
        return null;
    }

    /**
     * 获取下一个即将到来的空降片段
     * @param segments 片段列表
     * @param currentPositionMs 当前播放位置（毫秒）
     * @param lookAheadMs 提前多少毫秒提示
     * @return 即将到来的片段，没有则返回 null
     */
    public static Segment findUpcomingSegment(List<Segment> segments, long currentPositionMs, long lookAheadMs) {
        double currentSeconds = currentPositionMs / 1000.0;
        double lookAheadSeconds = lookAheadMs / 1000.0;
        
        for (Segment segment : segments) {
            double timeToStart = segment.segment[0] - currentSeconds;
            if (timeToStart > 0 && timeToStart <= lookAheadSeconds) {
                return segment;
            }
        }
        return null;
    }

    /**
     * 提交新的空降片段
     * API: POST /api/skipSegments
     * 
     * @param bvid BV号
     * @param cid CID (可选)
     * @param category 片段类别
     * @param startTime 开始时间（秒）
     * @param endTime 结束时间（秒）
     * @return 是否提交成功
     */
    public static boolean submitSegment(String bvid, String cid, String category, double startTime, double endTime) {
        try {
            String urlString = BASE_URL + "/skipSegments";
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "BilibiliSponsorBlockXposed/1.0");

            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("videoID", bvid);
            if (cid != null && !cid.isEmpty() && !cid.equals("0")) {
                requestBody.put("cid", cid);
            }
            requestBody.put("category", category);
            
            JSONArray segmentArray = new JSONArray();
            segmentArray.put(startTime);
            segmentArray.put(endTime);
            requestBody.put("segment", segmentArray);
            
            // 可选字段
            requestBody.put("actionType", "skip");

            String jsonBody = requestBody.toString();
            LogUtils.getInstance().log("SponsorBlockAPI", "提交片段: " + jsonBody);

            // 发送请求
            DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(jsonBody);
            outputStream.flush();
            outputStream.close();

            int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();
            
            LogUtils.getInstance().log("SponsorBlockAPI", 
                "提交响应: " + responseCode + " " + responseMessage);

            connection.disconnect();

            // 201 Created 表示成功创建
            if (responseCode == 201 || responseCode == 200) {
                // 清除该视频的缓存，以便下次获取时包含新提交的片段
                clearVideoCache(bvid, cid);
                return true;
            }
            
            // 读取错误响应
            if (responseCode >= 400) {
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream()));
                    StringBuilder errorResponse = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errorResponse.append(line);
                    }
                    reader.close();
                    LogUtils.getInstance().log("SponsorBlockAPI", 
                        "提交错误: " + errorResponse.toString());
                } catch (Exception e) {
                    // 忽略错误响应读取失败
                }
            }

            return false;

        } catch (Exception e) {
            LogUtils.getInstance().logError("SponsorBlockAPI", "提交片段失败", e);
            return false;
        }
    }

    /**
     * 清除特定视频的缓存
     */
    private static void clearVideoCache(String bvid, String cid) {
        String cacheKey = buildCacheKey(bvid, cid);
        segmentCache.remove(cacheKey);
        // 同时清除只有BV号的缓存
        segmentCache.remove(bvid);
    }
}
