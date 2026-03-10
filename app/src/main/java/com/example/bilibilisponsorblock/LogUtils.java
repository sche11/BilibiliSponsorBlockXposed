package com.example.bilibilisponsorblock;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LogUtils {

    private static final String LOG_FILE_NAME = "sponsorblock_log.txt";
    private static final long MAX_LOG_SIZE = 5 * 1024 * 1024;
    private static final int MAX_LOG_LINES = 1000;
    private static final String TAG = "SponsorBlock";

    private static LogUtils instance;
    private static Context appContext;
    private final ExecutorService logExecutor;
    private final Handler mainHandler;
    private final SimpleDateFormat dateFormat;
    private final List<LogEntry> logBuffer;
    private boolean isLoggingEnabled = true;
    private LogListener logListener;
    private static boolean isXposedEnvironment = false;

    public interface LogListener {
        void onNewLog(LogEntry entry);
    }

    public static class LogEntry {
        public final long timestamp;
        public final String level;
        public final String tag;
        public final String message;

        public LogEntry(long timestamp, String level, String tag, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.tag = tag;
            this.message = message;
        }

        public String getFormattedTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
            return sdf.format(new Date(timestamp));
        }

        @Override
        public String toString() {
            return String.format("[%s] %s/%s: %s", getFormattedTime(), level, tag, message);
        }
    }

    private LogUtils() {
        logExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        logBuffer = new ArrayList<>();

        // 检测是否在 Xposed 环境
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            isXposedEnvironment = true;
        } catch (ClassNotFoundException e) {
            isXposedEnvironment = false;
        }
    }

    public static synchronized LogUtils getInstance() {
        if (instance == null) {
            instance = new LogUtils();
        }
        return instance;
    }

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        getInstance().log("LogUtils", "日志系统初始化完成，Xposed环境: " + isXposedEnvironment);
    }

    public void setLoggingEnabled(boolean enabled) {
        this.isLoggingEnabled = enabled;
    }

    public boolean isLoggingEnabled() {
        return isLoggingEnabled;
    }

    public void setLogListener(LogListener listener) {
        this.logListener = listener;
    }

    private void logToXposed(String level, String tag, String message) {
        if (isXposedEnvironment) {
            try {
                // 使用反射调用 XposedBridge.log()
                Class<?> xposedBridgeClass = Class.forName("de.robv.android.xposed.XposedBridge");
                Method logMethod = xposedBridgeClass.getMethod("log", String.class);
                logMethod.invoke(null, "[" + level + "/" + tag + "] " + message);
            } catch (Exception e) {
                // Xposed 日志失败，使用 Android Log
                Log.println(level.equals("E") ? Log.ERROR : level.equals("D") ? Log.DEBUG : Log.INFO, tag, message);
            }
        } else {
            // 非 Xposed 环境使用 Android Log
            Log.println(level.equals("E") ? Log.ERROR : level.equals("D") ? Log.DEBUG : Log.INFO, tag, message);
        }
    }

    public void log(String tag, String message) {
        if (!isLoggingEnabled) return;

        LogEntry entry = new LogEntry(System.currentTimeMillis(), "D", tag, message);
        addToBuffer(entry);
        writeToFile(entry);
        logToXposed("D", tag, message);
    }

    public void logDebug(String tag, String message) {
        if (!isLoggingEnabled) return;

        LogEntry entry = new LogEntry(System.currentTimeMillis(), "D", tag, message);
        addToBuffer(entry);
        writeToFile(entry);
        logToXposed("D", tag, message);
    }

    public void logError(String tag, String message) {
        if (!isLoggingEnabled) return;

        LogEntry entry = new LogEntry(System.currentTimeMillis(), "E", tag, message);
        addToBuffer(entry);
        writeToFile(entry);
        logToXposed("E", tag, message);
    }

    public void logError(String tag, String message, Throwable throwable) {
        if (!isLoggingEnabled) return;

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        String stackTrace = sw.toString();

        LogEntry entry = new LogEntry(System.currentTimeMillis(), "E", tag, message + "\n" + stackTrace);
        addToBuffer(entry);
        writeToFile(entry);
        logToXposed("E", tag, message + " - " + throwable.getMessage());
    }

    private void addToBuffer(LogEntry entry) {
        synchronized (logBuffer) {
            logBuffer.add(entry);
            if (logBuffer.size() > MAX_LOG_LINES) {
                logBuffer.remove(0);
            }
        }

        if (logListener != null) {
            mainHandler.post(() -> logListener.onNewLog(entry));
        }
    }

    private void writeToFile(LogEntry entry) {
        logExecutor.execute(() -> {
            try {
                File logFile = getLogFile();
                if (logFile == null) return;

                if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                    clearLogFile();
                }

                FileWriter writer = new FileWriter(logFile, true);
                writer.write(entry.toString() + "\n");
                writer.close();
            } catch (IOException e) {
                Log.e(TAG, "写入日志失败: " + e.getMessage());
            }
        });
    }

    private File getLogFile() {
        if (appContext == null) return null;

        try {
            File dir = appContext.getExternalFilesDir(null);
            if (dir == null) {
                dir = appContext.getFilesDir();
            }
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            return new File(dir, LOG_FILE_NAME);
        } catch (Exception e) {
            Log.e(TAG, "获取日志文件失败: " + e.getMessage());
            return null;
        }
    }

    public List<LogEntry> getLogBuffer() {
        synchronized (logBuffer) {
            return new ArrayList<>(logBuffer);
        }
    }

    public List<LogEntry> readLogFile() {
        List<LogEntry> entries = new ArrayList<>();
        File logFile = getLogFile();

        if (logFile == null || !logFile.exists()) {
            return entries;
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(logFile));
            String line;
            while ((line = reader.readLine()) != null) {
                LogEntry entry = parseLogLine(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            reader.close();
        } catch (IOException e) {
            logError("LogUtils", "读取日志文件失败", e);
        }

        return entries;
    }

    private LogEntry parseLogLine(String line) {
        try {
            if (!line.startsWith("[")) return null;

            int timeEnd = line.indexOf("]");
            if (timeEnd == -1) return null;

            String timeStr = line.substring(1, timeEnd);
            Date date = dateFormat.parse(timeStr);
            long timestamp = date != null ? date.getTime() : System.currentTimeMillis();

            int levelStart = timeEnd + 2;
            int levelEnd = line.indexOf("/", levelStart);
            if (levelEnd == -1) return null;

            String level = line.substring(levelStart, levelEnd);

            int tagStart = levelEnd + 1;
            int tagEnd = line.indexOf(": ", tagStart);
            if (tagEnd == -1) return null;

            String tag = line.substring(tagStart, tagEnd);
            String message = line.substring(tagEnd + 2);

            return new LogEntry(timestamp, level, tag, message);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean clearLogFile() {
        File logFile = getLogFile();
        if (logFile != null && logFile.exists()) {
            return logFile.delete();
        }
        return true;
    }

    public void clearBuffer() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
    }

    public String exportLogs() {
        File logFile = getLogFile();
        if (logFile == null || !logFile.exists()) {
            return null;
        }

        try {
            File exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }

            String exportFileName = "SponsorBlock_Logs_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
            File exportFile = new File(exportDir, exportFileName);

            FileInputStream fis = new FileInputStream(logFile);
            FileOutputStream fos = new FileOutputStream(exportFile);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }

            fis.close();
            fos.close();

            return exportFile.getAbsolutePath();
        } catch (IOException e) {
            logError("LogUtils", "导出日志失败", e);
            return null;
        }
    }

    public String getLogFilePath() {
        File logFile = getLogFile();
        return logFile != null ? logFile.getAbsolutePath() : null;
    }

    public long getLogFileSize() {
        File logFile = getLogFile();
        return logFile != null && logFile.exists() ? logFile.length() : 0;
    }

    public String getFormattedLogSize() {
        long size = getLogFileSize();
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.2f KB", size / 1024.0);
        } else {
            return String.format("%.2f MB", size / (1024.0 * 1024.0));
        }
    }
}
