package com.example.bilibilisponsorblock;

/**
 * 跳过模式枚举
 * 定义片段的不同跳过行为
 */
public enum SkipMode {
    ALWAYS(0, "总是跳过", "自动跳过片段"),
    ONCE(1, "仅跳过一次", "仅跳过当前这一次，下次播放时不再自动跳过"),
    MANUAL(2, "手动跳过", "显示跳过提示，由用户手动确认"),
    NEVER(3, "不跳过", "完全不跳过该类别片段");

    private final int value;
    private final String displayName;
    private final String description;

    SkipMode(int value, String displayName, String description) {
        this.value = value;
        this.displayName = displayName;
        this.description = description;
    }

    public int getValue() {
        return value;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 从整数值获取枚举
     */
    public static SkipMode fromValue(int value) {
        for (SkipMode mode : values()) {
            if (mode.value == value) {
                return mode;
            }
        }
        return ALWAYS; // 默认总是跳过
    }

    /**
     * 获取所有显示名称
     */
    public static String[] getDisplayNames() {
        SkipMode[] modes = values();
        String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            names[i] = modes[i].displayName;
        }
        return names;
    }

    /**
     * 获取所有值字符串
     */
    public static String[] getValueStrings() {
        SkipMode[] modes = values();
        String[] values = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            values[i] = String.valueOf(modes[i].value);
        }
        return values;
    }
}
