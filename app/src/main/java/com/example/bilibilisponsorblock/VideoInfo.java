package com.example.bilibilisponsorblock;

/**
 * 视频信息类
 * 参考 PC 版的视频 ID 管理方式
 */
public class VideoInfo {
    private String bvid;
    private String cid;
    private String aid;
    private long lastUpdateTime;

    public VideoInfo() {
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public String getBvid() {
        return bvid;
    }

    public void setBvid(String bvid) {
        this.bvid = bvid;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public String getCid() {
        return cid;
    }

    public void setCid(String cid) {
        this.cid = cid;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * 检查视频信息是否完整
     */
    public boolean isComplete() {
        return bvid != null && !bvid.isEmpty() && cid != null && !cid.isEmpty();
    }

    /**
     * 检查是否是同一个视频
     */
    public boolean isSameVideo(String otherBvid, String otherCid) {
        if (bvid == null || otherBvid == null) return false;
        if (cid == null || otherCid == null) {
            return bvid.equals(otherBvid);
        }
        return bvid.equals(otherBvid) && cid.equals(otherCid);
    }

    /**
     * 重置视频信息
     */
    public void reset() {
        bvid = null;
        cid = null;
        aid = null;
        lastUpdateTime = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("VideoInfo{bvid='%s', cid='%s', aid='%s'}", bvid, cid, aid);
    }
}
