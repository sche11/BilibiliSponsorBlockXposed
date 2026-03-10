package com.example.bilibilisponsorblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LogActivity extends Activity implements LogUtils.LogListener {

    private ListView logListView;
    private LogAdapter logAdapter;
    private List<LogUtils.LogEntry> logEntries;
    private Handler uiHandler;
    private TextView emptyView;
    private boolean autoScroll = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        setTitle("日志查看");
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        uiHandler = new Handler(Looper.getMainLooper());
        logEntries = new ArrayList<>();

        initViews();
        loadLogs();

        // 注册日志监听器
        LogUtils.getInstance().setLogListener(this);
    }

    private void initViews() {
        logListView = findViewById(R.id.log_list);
        emptyView = findViewById(R.id.empty_view);

        logAdapter = new LogAdapter(this, logEntries);
        logListView.setAdapter(logAdapter);

        // 长按复制
        logListView.setOnItemLongClickListener((parent, view, position, id) -> {
            LogUtils.LogEntry entry = logEntries.get(position);
            copyToClipboard(entry.toString());
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            return true;
        });

        // 点击暂停自动滚动
        logListView.setOnItemClickListener((parent, view, position, id) -> {
            autoScroll = false;
            Toast.makeText(this, "自动滚动已暂停，滑动到底部恢复", Toast.LENGTH_SHORT).show();
        });

        // 滚动监听
        logListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (totalItemCount > 0) {
                    int lastVisibleItem = firstVisibleItem + visibleItemCount;
                    if (lastVisibleItem >= totalItemCount - 1) {
                        autoScroll = true;
                    }
                }
            }
        });
    }

    private void loadLogs() {
        new Thread(() -> {
            List<LogUtils.LogEntry> entries = LogUtils.getInstance().readLogFile();
            uiHandler.post(() -> {
                logEntries.clear();
                logEntries.addAll(entries);
                logAdapter.notifyDataSetChanged();
                updateEmptyView();
                if (autoScroll && !logEntries.isEmpty()) {
                    scrollToBottom();
                }
            });
        }).start();
    }

    @Override
    public void onNewLog(LogUtils.LogEntry entry) {
        uiHandler.post(() -> {
            logEntries.add(entry);
            logAdapter.notifyDataSetChanged();
            updateEmptyView();
            if (autoScroll) {
                scrollToBottom();
            }
        });
    }

    private void updateEmptyView() {
        if (logEntries.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            logListView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            logListView.setVisibility(View.VISIBLE);
        }
    }

    private void scrollToBottom() {
        uiHandler.postDelayed(() -> {
            if (logListView != null && !logEntries.isEmpty()) {
                logListView.setSelection(logAdapter.getCount() - 1);
            }
        }, 100);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_log, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_refresh) {
            loadLogs();
            Toast.makeText(this, "日志已刷新", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_clear) {
            showClearConfirmDialog();
            return true;
        } else if (id == R.id.action_export) {
            exportLogs();
            return true;
        } else if (id == R.id.action_share) {
            shareLogs();
            return true;
        } else if (id == R.id.action_auto_scroll) {
            autoScroll = !autoScroll;
            item.setChecked(autoScroll);
            Toast.makeText(this, autoScroll ? "自动滚动已开启" : "自动滚动已关闭", Toast.LENGTH_SHORT).show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showClearConfirmDialog() {
        new AlertDialog.Builder(this)
                .setTitle("清空日志")
                .setMessage("确定要清空所有日志吗？此操作不可恢复。")
                .setPositiveButton("确定", (dialog, which) -> {
                    LogUtils.getInstance().clearLogFile();
                    LogUtils.getInstance().clearBuffer();
                    logEntries.clear();
                    logAdapter.notifyDataSetChanged();
                    updateEmptyView();
                    Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportLogs() {
        new Thread(() -> {
            String path = LogUtils.getInstance().exportLogs();
            uiHandler.post(() -> {
                if (path != null) {
                    Toast.makeText(this, "日志已导出到: " + path, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void shareLogs() {
        new Thread(() -> {
            String path = LogUtils.getInstance().exportLogs();
            uiHandler.post(() -> {
                if (path != null) {
                    File file = new File(path);
                    Uri uri = Uri.fromFile(file);
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, "SponsorBlock 日志");
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent, "分享日志"));
                } else {
                    Toast.makeText(this, "分享失败", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void copyToClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("日志", text);
        clipboard.setPrimaryClip(clip);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LogUtils.getInstance().setLogListener(null);
    }

    // 日志适配器
    private static class LogAdapter extends BaseAdapter {
        private final Context context;
        private final List<LogUtils.LogEntry> entries;

        public LogAdapter(Context context, List<LogUtils.LogEntry> entries) {
            this.context = context;
            this.entries = entries;
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView textView;
            if (convertView == null) {
                textView = new TextView(context);
                textView.setPadding(16, 8, 16, 8);
                textView.setTextSize(12);
            } else {
                textView = (TextView) convertView;
            }

            LogUtils.LogEntry entry = entries.get(position);
            textView.setText(entry.toString());

            // 根据日志级别设置颜色
            switch (entry.level) {
                case "E":
                    textView.setTextColor(0xFFFF4444); // 红色
                    break;
                case "D":
                    textView.setTextColor(0xFF2196F3); // 蓝色
                    break;
                default:
                    textView.setTextColor(0xFF333333); // 深灰
                    break;
            }

            return textView;
        }
    }
}
