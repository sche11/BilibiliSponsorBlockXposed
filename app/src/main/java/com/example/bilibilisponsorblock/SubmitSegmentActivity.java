package com.example.bilibilisponsorblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 提交片段 Activity
 * 允许用户标记和提交新的空降片段
 * 使用莫奈取色 Material You 设计风格
 */
public class SubmitSegmentActivity extends Activity {

    private static final String TAG = "SubmitSegment";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // 动态莫奈颜色（从系统壁纸获取）
    private int monetPrimary;
    private int monetOnPrimary;
    private int monetPrimaryContainer;
    private int monetOnPrimaryContainer;
    private int monetSurface;
    private int monetSurfaceVariant;
    private int monetOutline;
    private int monetSecondary;

    // 类别选项
    private static final String[] CATEGORIES = {
        "sponsor", "selfpromo", "intro", "outro", 
        "interaction", "preview", "filler", "music_offtopic"
    };
    
    private static final String[] CATEGORY_NAMES = {
        "赞助商广告", "自我推广", "片头", "片尾",
        "互动提醒", "预览/回顾", "填充内容", "非音乐部分"
    };

    private EditText etBvid, etCid, etStartTime, etEndTime;
    private Spinner spinnerCategory;
    private TextView tvCategoryDescription;
    private Button btnSubmit, btnCurrentTime;
    
    private String selectedCategory = "sponsor";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 初始化日志
        LogUtils.init(this);
        
        // 初始化动态莫奈颜色
        initMonetColors();
        
        // 应用莫奈主题
        applyMonetTheme();
        
        // 创建UI
        createUI();
        
        // 从Intent获取视频信息
        parseIntent(getIntent());
        
        // 设置返回按钮
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle("提交空降片段");
            getActionBar().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(monetPrimary));
        }
    }
    
    /**
     * 初始化动态莫奈颜色（从系统壁纸取色）
     */
    private void initMonetColors() {
        monetPrimary = MonetColorUtils.getMonetPrimaryColor(this);
        monetOnPrimary = 0xFFFFFFFF;
        monetPrimaryContainer = MonetColorUtils.getMonetContainerColor(this);
        monetOnPrimaryContainer = MonetColorUtils.getMonetPrimaryColor(this);
        monetSurface = MonetColorUtils.getMonetSurfaceColor(this);
        monetSurfaceVariant = MonetColorUtils.getMonetBackgroundColor(this);
        monetOutline = MonetColorUtils.getMonetOutlineColor(this);
        monetSecondary = MonetColorUtils.getMonetSecondaryColor(this);
    }
    
    /**
     * 应用莫奈主题
     */
    private void applyMonetTheme() {
        getWindow().setStatusBarColor(monetPrimary);
        getWindow().setBackgroundDrawable(
            new android.graphics.drawable.ColorDrawable(monetSurface));
    }

    private void createUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(monetSurface);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        layout.setBackgroundColor(monetSurface);
        
        // 标题 - 使用莫奈主色
        TextView tvTitle = new TextView(this);
        tvTitle.setText("标记空降片段");
        tvTitle.setTextSize(28);
        tvTitle.setTextColor(monetPrimary);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 8);
        layout.addView(tvTitle);
        
        // 副标题
        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("帮助社区标记可跳过的片段");
        tvSubtitle.setTextSize(14);
        tvSubtitle.setTextColor(monetSecondary);
        tvSubtitle.setPadding(0, 0, 0, 32);
        layout.addView(tvSubtitle);
        
        // 视频信息卡片
        layout.addView(createCardHeader("视频信息"));
        
        // BV号输入 - 莫奈风格
        etBvid = new EditText(this);
        layout.addView(createStyledEditTextContainer("BV号", "例如: BV1xx411c7mD", etBvid));
        
        // CID输入
        etCid = new EditText(this);
        layout.addView(createStyledEditTextContainer("CID (可选)", "视频的CID编号", etCid));
        
        // 添加间距
        layout.addView(createSpacing(24));
        
        // 片段信息卡片
        layout.addView(createCardHeader("片段信息"));
        
        // 类别选择 - 莫奈风格
        TextView tvCategoryLabel = new TextView(this);
        tvCategoryLabel.setText("片段类别");
        tvCategoryLabel.setTextColor(monetSecondary);
        tvCategoryLabel.setTextSize(12);
        tvCategoryLabel.setPadding(16, 16, 0, 8);
        layout.addView(tvCategoryLabel);
        
        spinnerCategory = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_spinner_item, CATEGORY_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(adapter);
        spinnerCategory.setBackground(createMonetOutlineBackground());
        spinnerCategory.setPadding(16, 16, 16, 16);
        spinnerCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedCategory = CATEGORIES[position];
                updateCategoryDescription(position);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        layout.addView(spinnerCategory);
        
        // 类别描述 - 莫奈容器风格
        tvCategoryDescription = new TextView(this);
        tvCategoryDescription.setTextSize(13);
        tvCategoryDescription.setPadding(20, 16, 20, 16);
        tvCategoryDescription.setBackgroundColor(monetPrimaryContainer);
        tvCategoryDescription.setTextColor(monetOnPrimaryContainer);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descParams.setMargins(0, 12, 0, 16);
        tvCategoryDescription.setLayoutParams(descParams);
        layout.addView(tvCategoryDescription);
        updateCategoryDescription(0);
        
        // 时间选择区域
        LinearLayout timeLayout = new LinearLayout(this);
        timeLayout.setOrientation(LinearLayout.HORIZONTAL);
        
        // 开始时间
        LinearLayout startContainer = new LinearLayout(this);
        startContainer.setOrientation(LinearLayout.VERTICAL);
        startContainer.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        
        TextView tvStartLabel = new TextView(this);
        tvStartLabel.setText("开始时间");
        tvStartLabel.setTextColor(monetSecondary);
        tvStartLabel.setTextSize(12);
        tvStartLabel.setPadding(16, 0, 0, 8);
        startContainer.addView(tvStartLabel);
        
        etStartTime = new EditText(this);
        etStartTime.setHint("0.0");
        etStartTime.setBackground(createMonetOutlineBackground());
        etStartTime.setPadding(20, 20, 20, 20);
        etStartTime.setTextColor(0xFF1C1B1F);
        etStartTime.setHintTextColor(monetOutline);
        etStartTime.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | 
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        startContainer.addView(etStartTime);
        
        timeLayout.addView(startContainer);
        
        // 间距
        View timeSpacing = new View(this);
        timeSpacing.setLayoutParams(new LinearLayout.LayoutParams(24, 1));
        timeLayout.addView(timeSpacing);
        
        // 结束时间
        LinearLayout endContainer = new LinearLayout(this);
        endContainer.setOrientation(LinearLayout.VERTICAL);
        endContainer.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        
        TextView tvEndLabel = new TextView(this);
        tvEndLabel.setText("结束时间");
        tvEndLabel.setTextColor(monetSecondary);
        tvEndLabel.setTextSize(12);
        tvEndLabel.setPadding(16, 0, 0, 8);
        endContainer.addView(tvEndLabel);
        
        etEndTime = new EditText(this);
        etEndTime.setHint("30.0");
        etEndTime.setBackground(createMonetOutlineBackground());
        etEndTime.setPadding(20, 20, 20, 20);
        etEndTime.setTextColor(0xFF1C1B1F);
        etEndTime.setHintTextColor(monetOutline);
        etEndTime.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | 
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        endContainer.addView(etEndTime);
        
        timeLayout.addView(endContainer);
        layout.addView(timeLayout);
        
        // 获取当前时间按钮 - 莫奈风格
        btnCurrentTime = new Button(this);
        btnCurrentTime.setText("获取当前播放时间");
        btnCurrentTime.setTextColor(monetPrimary);
        btnCurrentTime.setBackground(createMonetTonalButtonBackground());
        btnCurrentTime.setAllCaps(false);
        btnCurrentTime.setOnClickListener(v -> getCurrentTime());
        LinearLayout.LayoutParams currentTimeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        currentTimeParams.setMargins(0, 16, 0, 24);
        btnCurrentTime.setLayoutParams(currentTimeParams);
        layout.addView(btnCurrentTime);
        
        // 添加间距
        layout.addView(createSpacing(16));
        
        // 提交按钮 - 莫奈主按钮风格
        btnSubmit = new Button(this);
        btnSubmit.setText("提交片段");
        btnSubmit.setTextColor(monetOnPrimary);
        btnSubmit.setTextSize(16);
        btnSubmit.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSubmit.setBackground(createMonetFilledButtonBackground());
        btnSubmit.setAllCaps(false);
        btnSubmit.setElevation(4);
        btnSubmit.setOnClickListener(v -> submitSegment());
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        submitParams.setMargins(0, 8, 0, 24);
        btnSubmit.setLayoutParams(submitParams);
        layout.addView(btnSubmit);
        
        // 提示信息
        TextView tvNote = new TextView(this);
        tvNote.setText("\n提示:\n" +
            "• 请确保时间准确，避免标记错误\n" +
            "• 片段时长建议至少0.5秒\n" +
            "• 提交后可能需要审核才能生效\n" +
            "• API服务器: " + Preferences.getApiServer());
        tvNote.setTextSize(12);
        tvNote.setPadding(0, 32, 0, 0);
        layout.addView(tvNote);
        
        scrollView.addView(layout);
        setContentView(scrollView);
    }
    
    /**
     * 创建卡片标题
     */
    private TextView createCardHeader(String title) {
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextSize(16);
        header.setTextColor(monetPrimary);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setPadding(16, 24, 0, 16);
        return header;
    }
    
    /**
     * 创建莫奈风格的输入框容器
     */
    private LinearLayout createStyledEditTextContainer(String label, String hint, EditText editText) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        
        if (!label.isEmpty()) {
            TextView labelView = new TextView(this);
            labelView.setText(label);
            labelView.setTextColor(monetSecondary);
            labelView.setTextSize(12);
            labelView.setPadding(16, 16, 0, 8);
            container.addView(labelView);
        }
        
        editText.setHint(hint);
        editText.setBackground(createMonetOutlineBackground());
        editText.setPadding(20, 20, 20, 20);
        editText.setTextColor(0xFF1C1B1F);
        editText.setHintTextColor(monetOutline);
        
        container.addView(editText);
        return container;
    }
    
    /**
     * 创建莫奈轮廓背景
     */
    private android.graphics.drawable.Drawable createMonetOutlineBackground() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(monetSurface);
        drawable.setCornerRadius(12);
        drawable.setStroke(2, monetOutline);
        return drawable;
    }
    
    /**
     * 创建莫奈填充按钮背景
     */
    private android.graphics.drawable.Drawable createMonetFilledButtonBackground() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(monetPrimary);
        drawable.setCornerRadius(24);
        return drawable;
    }
    
    /**
     * 创建莫奈色调按钮背景
     */
    private android.graphics.drawable.Drawable createMonetTonalButtonBackground() {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(monetPrimaryContainer);
        drawable.setCornerRadius(24);
        return drawable;
    }
    
    /**
     * 创建间距
     */
    private View createSpacing(int height) {
        View spacing = new View(this);
        spacing.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, height));
        return spacing;
    }

    private void updateCategoryDescription(int position) {
        String[] descriptions = {
            "赞助商广告: 付费推广内容、品牌合作等",
            "自我推广: UP主宣传自己的其他视频或频道",
            "片头: 开场动画、固定片头介绍等",
            "片尾: 结束画面、订阅提醒、相关视频推荐等",
            "互动提醒: 点赞、投币、收藏、关注等提醒",
            "预览/回顾: 内容预览、前情回顾、下期预告等",
            "填充内容: 与主题无关的闲聊、等待时间等",
            "非音乐部分: 音乐视频中的谈话、非音乐片段"
        };
        tvCategoryDescription.setText(descriptions[position]);
    }

    private void parseIntent(Intent intent) {
        if (intent != null) {
            String bvid = intent.getStringExtra("bvid");
            String cid = intent.getStringExtra("cid");
            double startTime = intent.getDoubleExtra("start_time", -1);
            double endTime = intent.getDoubleExtra("end_time", -1);
            
            if (bvid != null) {
                etBvid.setText(bvid);
            }
            if (cid != null) {
                etCid.setText(cid);
            }
            if (startTime >= 0) {
                etStartTime.setText(String.valueOf(startTime));
            }
            if (endTime >= 0) {
                etEndTime.setText(String.valueOf(endTime));
            }
        }
    }

    private void getCurrentTime() {
        // 从PlayerHook获取当前播放时间
        long currentMs = PlayerHook.getCurrentPosition();
        if (currentMs > 0) {
            double currentSec = currentMs / 1000.0;
            etStartTime.setText(String.format("%.2f", currentSec));
            Toast.makeText(this, "已获取当前时间: " + String.format("%.2f", currentSec) + "秒", 
                Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "无法获取当前播放时间，请手动输入", Toast.LENGTH_SHORT).show();
        }
    }

    private void submitSegment() {
        // 验证输入
        String bvid = etBvid.getText().toString().trim();
        String cid = etCid.getText().toString().trim();
        String startStr = etStartTime.getText().toString().trim();
        String endStr = etEndTime.getText().toString().trim();
        
        if (bvid.isEmpty()) {
            etBvid.setError("请输入BV号");
            return;
        }
        
        if (!bvid.startsWith("BV")) {
            etBvid.setError("BV号格式错误，应以BV开头");
            return;
        }
        
        if (startStr.isEmpty()) {
            etStartTime.setError("请输入开始时间");
            return;
        }
        
        if (endStr.isEmpty()) {
            etEndTime.setError("请输入结束时间");
            return;
        }
        
        double startTime, endTime;
        try {
            startTime = Double.parseDouble(startStr);
            endTime = Double.parseDouble(endStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "时间格式错误", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (startTime < 0 || endTime <= 0) {
            Toast.makeText(this, "时间必须大于0", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (endTime <= startTime) {
            Toast.makeText(this, "结束时间必须大于开始时间", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (endTime - startTime < 0.5) {
            Toast.makeText(this, "片段时长至少0.5秒", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 显示确认对话框
        new AlertDialog.Builder(this)
            .setTitle("确认提交")
            .setMessage(String.format("类别: %s\n开始: %.2f秒\n结束: %.2f秒\n\n确认提交此片段?",
                CATEGORY_NAMES[getCategoryIndex(selectedCategory)], startTime, endTime))
            .setPositiveButton("提交", (dialog, which) -> {
                doSubmit(bvid, cid.isEmpty() ? null : cid, selectedCategory, startTime, endTime);
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private int getCategoryIndex(String category) {
        for (int i = 0; i < CATEGORIES.length; i++) {
            if (CATEGORIES[i].equals(category)) {
                return i;
            }
        }
        return 0;
    }

    private void doSubmit(String bvid, String cid, String category, double startTime, double endTime) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage("正在提交...");
        progress.setCancelable(false);
        progress.show();
        
        executor.execute(() -> {
            try {
                boolean success = SponsorBlockAPI.submitSegment(bvid, cid, category, startTime, endTime);
                
                mainHandler.post(() -> {
                    progress.dismiss();
                    if (success) {
                        Toast.makeText(this, "提交成功！感谢你的贡献", Toast.LENGTH_LONG).show();
                        LogUtils.getInstance().log(TAG, "提交片段成功: " + bvid + " " + category);
                        finish();
                    } else {
                        Toast.makeText(this, "提交失败，请检查网络或稍后重试", Toast.LENGTH_LONG).show();
                        LogUtils.getInstance().log(TAG, "提交片段失败: " + bvid);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    progress.dismiss();
                    Toast.makeText(this, "提交出错: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    LogUtils.getInstance().logError(TAG, "提交片段异常", e);
                });
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
