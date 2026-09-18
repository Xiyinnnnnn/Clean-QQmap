package com.xiyin.navi;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.xiyin.navi.core.AppConst;
import com.xiyin.navi.core.TransitSearch;

import java.util.Locale;

/**
 * 公交换乘「站点导航」详情页（v4.1 新增）。
 *
 * <p>不做地图，只按官方返回的逐段数据把行程讲清楚：
 * <ol>
 *   <li>步行段：距离 / 耗时 / 逐条指引（朝东行进58米右转…）</li>
 *   <li>乘车段：线路名、上车站（含出入口）、下车站、途经站点、运营时间</li>
 * </ol>
 * 数据全部来自 {@link TransitSearch}，本页只负责排版展示。
 */
public class TransitDetailActivity extends AppCompatActivity {

    private LinearLayout container;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transit_detail);

        container = findViewById(R.id.detail_container);
        TextView title = findViewById(R.id.detail_title);

        TransitSearch.Plan plan = (TransitSearch.Plan)
                getIntent().getSerializableExtra(AppConst.EXTRA_PLAN);
        String destName = getIntent().getStringExtra(AppConst.EXTRA_DEST_NAME);

        if (title != null) {
            title.setText(getString(R.string.transit_detail_title_to)
                    + (destName == null || destName.isEmpty() ? "目的地" : destName));
        }
        if (plan == null) {
            addSimpleText(getString(R.string.transit_no_result), 14, Color.RED, false);
            return;
        }
        render(plan);
    }

    /** 把整条方案按分段渲染成"站点导航"文本流。 */
    private void render(TransitSearch.Plan plan) {
        // 概览行
        StringBuilder head = new StringBuilder();
        head.append("历时 ").append(plan.durationMin).append(" 分钟 · ")
                .append(String.format(Locale.CHINA, "%.1f", plan.distanceM / 1000.0)).append(" 公里");
        if (plan.priceCent >= 0) {
            head.append(" · ¥").append(String.format(Locale.CHINA, "%.2f", plan.priceCent / 100.0));
        }
        addSimpleText(head.toString(), 14, 0xFF666666, false);
        addSpacer(10);

        for (int i = 0; i < plan.segments.size(); i++) {
            TransitSearch.Seg seg = plan.segments.get(i);
            if (seg.transit) {
                addRideSegment(seg, i + 1);
            } else {
                addWalkSegment(seg, i + 1);
            }
            addSpacer(12);
        }
    }

    /** 步行段：一行摘要 + 逐条指引。 */
    private void addWalkSegment(TransitSearch.Seg seg, int no) {
        StringBuilder sb = new StringBuilder();
        sb.append(no).append(". 步行 ").append(seg.walkDistanceM).append(" 米");
        if (seg.walkDurationMin > 0) {
            sb.append(" · 约 ").append(seg.walkDurationMin).append(" 分钟");
        }
        if (!seg.walkDirection.isEmpty()) {
            sb.append(" · 向").append(seg.walkDirection);
        }
        addSimpleText(sb.toString(), 14, 0xFF1B5E20, true);
        if (!seg.walkDesc.isEmpty()) {
            addSimpleText(seg.walkDesc, 13, 0xFF666666, false);
        }
    }

    /** 乘车段：线路卡片。 */
    private void addRideSegment(TransitSearch.Seg seg, int no) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(lp);
        card.setBackgroundColor(0xFFF0F5FF);
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);

        // 第 1 行：序号 + 线路名 + 类型
        String type = "SUBWAY".equals(seg.vehicle) ? "地铁"
                : ("BUS".equals(seg.vehicle) ? "公交" : "");
        TextView lineName = new TextView(this);
        lineName.setText(no + ". " + seg.lineTitle + (type.isEmpty() ? "" : "（" + type + "）"));
        lineName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        lineName.setTextColor(0xFF3B7BFF);
        lineName.setTypeface(null, Typeface.BOLD);
        card.addView(lineName);

        // 上车站
        StringBuilder on = new StringBuilder("上车站：").append(seg.getOnName);
        if (!seg.getOnExit.isEmpty()) {
            on.append("　出入口：").append(seg.getOnExit);
        }
        addTextTo(card, on.toString(), 14, 0xFF222222, false);

        // 途经站点
        if (seg.stationSeq.size() > 1) {
            StringBuilder stations = new StringBuilder();
            for (int i = 0; i < seg.stationSeq.size(); i++) {
                if (i > 0) {
                    stations.append(" → ");
                }
                stations.append(seg.stationSeq.get(i));
            }
            addTextTo(card, "途经 " + Math.max(seg.stationSeq.size() - 1, 0) + " 站："
                    + stations, 13, 0xFF444444, false);
        }

        // 下车站
        addTextTo(card, "下车站：" + seg.getOffName, 14, 0xFF222222, false);

        // 运营信息
        StringBuilder meta = new StringBuilder();
        if (seg.stationCount > 0) {
            meta.append("乘 ").append(seg.stationCount).append(" 站");
        }
        if (seg.lineDurationMin > 0) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append("约 ").append(seg.lineDurationMin).append(" 分钟");
        }
        if (!seg.startTime.isEmpty() && !seg.endTime.isEmpty()) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append("运营 ").append(seg.startTime).append("~").append(seg.endTime);
        }
        if (meta.length() > 0) {
            addTextTo(card, meta.toString(), 12, 0xFF888888, false);
        }

        container.addView(card);
    }

    private void addSimpleText(String text, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(null, Typeface.BOLD);
        }
        container.addView(tv);
    }

    private void addTextTo(LinearLayout parent, String text, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setLineSpacing(dp(2), 1f);
        if (bold) {
            tv.setTypeface(null, Typeface.BOLD);
        }
        parent.addView(tv);
    }

    private void addSpacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(dp)));
        container.addView(v);
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
