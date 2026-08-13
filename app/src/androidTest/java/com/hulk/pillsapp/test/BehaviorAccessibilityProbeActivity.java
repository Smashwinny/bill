package com.hulk.pillsapp.test;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 仅存在于 androidTest APK 的独立进程可访问性探针；不发起支付，也不绕过采集链路直接写库。
 * 主应用服务应像处理外部 App 一样，把这组事件写成待确认候选。
 * 使用纯 Java 避免测试 APK 单独启动时依赖目标 APK 提供 Kotlin runtime。
 */
public final class BehaviorAccessibilityProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float density = getResources().getDisplayMetrics().density;
        int padding = (int) (24 * density);

        TextView heading = new TextView(this);
        heading.setText("无障碍事件链路测试（不涉及真实资金）");
        heading.setTextSize(20f);

        TextView status = new TextView(this);
        status.setText("尚未付款");
        status.setTextSize(24f);
        status.setContentDescription("尚未付款");
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        Button pay = new Button(this);
        pay.setText("确认付款");
        pay.setContentDescription("确认付款");
        pay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 先让系统送达按钮的 TYPE_VIEW_CLICKED，再只发送一个明确终态事件。
                status.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("支付成功 ￥0.02");
                        status.setContentDescription("支付成功 ￥0.02");
                        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                    }
                }, 250L);
            }
        });

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);
        layout.addView(heading, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layout.addView(pay, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        layout.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        setContentView(layout);
    }
}
