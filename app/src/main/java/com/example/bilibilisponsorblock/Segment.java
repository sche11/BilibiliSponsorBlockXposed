package com.example.bilibilisponsorblock;

/**
 * 视频片段数据类
 * 对应 SponsorBlock API 返回的片段数据
 */
public class Segment {
    // 片段唯一标识
    public String uuid = "";
    
    // 类别：sponsor, selfpromo, intro, outro, interaction, preview, filler
    public String category = "sponsor";
    
    // 时间段 [开始时间(秒), 结束时间(秒)]
    public double[] segment = new double[2];
    
    // 动作类型：skip, mute, full
    public String actionType = "skip";
    
    // 是否已跳过（运行时状态）
    public transient boolean skipped = false;
    
    /**
     * 获取开始时间（秒）
     */
    public double getStartTime() {
        return segment != null && segment.length > 0 ? segment[0] : 0;
    }
    
    /**
     * 获取结束时间（秒）
     */
    public double getEndTime() {
        return segment != null && segment.length > 1 ? segment[1] : 0;
    }
    
    /**
     * 获取片段持续时间（秒）
     */
    public double getDuration() {
        return getEndTime() - getStartTime();
    }
    
    /**
     * 检查指定时间是否在片段内
     */
    public boolean containsTime(double timeInSeconds) {
        return timeInSeconds >= getStartTime() && timeInSeconds < getEndTime();
    }
    
    /**
     * 获取类别显示名称
     */
    public String getCategoryName() {
        switch (category) {
            case "sponsor": return "赞助商广告";
            case "selfpromo": return "自我推广";
            case "intro": return "片头";
            case "outro": return "片尾";
            case "interaction": return "互动提醒";
            case "preview": return "预览/回顾";
            case "filler": return "填充内容";
            case "music_offtopic": return "非音乐部分";
            default: return "其他";
        }
    }
    
    @Override
    public String toString() {
        return String.format("Segment{uuid='%s', category='%s', time=[%.2f, %.2f], action='%s'}",
            uuid, category, getStartTime(), getEndTime(), actionType);
    }
}
