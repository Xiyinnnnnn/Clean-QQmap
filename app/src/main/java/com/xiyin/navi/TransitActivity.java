package com.xiyin.navi;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.xiyin.navi.core.AppConst;
import com.xiyin.navi.core.KeyManager;
import com.xiyin.navi.core.TransitSearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 公共交通（公交 / 地铁）换乘方案列表页（v4.1 新增）。
 *
 * <p>与驾车 / 骑行 / 步行保持同一设计：由 MainActivity 以 Intent 传入起终点，
 * 本页只负责展示腾讯官方返回的换乘方案；点击某条方案进入
 * {@link TransitDetailActivity} 查看逐段站点导航。
 */
public class TransitActivity extends AppCompatActivity {

    private static final String TAG = "MyNaviTransit";

    private ScrollView scroll;
    private LinearLayout container;
    private TextView status;

    private double startLat, startLng, destLat, destLng;
    private String destName;

    private final TransitSearch transitSearch = new TransitSearch();
    private final List<TransitSearch.Plan> plans = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transit);

        scroll = findViewById(R.id.transit_scroll);
        container = findViewById(R.id.transit_container);
        status = findViewById(R.id.transit_status);

        TextView title = findViewById(R.id.transit_title);
        Intent intent = getIntent();
        startLat = intent.getDoubleExtra(AppConst.EXTRA_START_LAT, 0);
        startLng = intent.getDoubleExtra(AppConst.EXTRA_START_LNG, 0);
        destLat = intent.getDoubleExtra(AppConst.EXTRA_DEST_LAT, 0);
        destLng = intent.getDoubleExtra(AppConst.EXTRA_DEST_LNG, 0);
        destName = intent.getStringExtra(AppConst.EXTRA_DEST_NAME);
        if (title != null) {
            title.setText(getString(R.string.transit_title_to)
                    + (destName == null || destName.isEmpty() ? "目的地" : destName));
        }
        Log.i(TAG, "进入换乘列表 起点=" + startLat + "," + startLng
                + " 终点=" + destLat + "," + destLng);

        queryPlans();
    }

    private void queryPlans() {
        status.setText(R.string.transit_planning);
        transitSearch.plan(this, startLat, startLng, destLat, destLng,
                new TransitSearch.Callback() {
                    @Override
                    public void onResult(List<TransitSearch.Plan> result) {
                        plans.clear();
                        plans.addAll(result);
                        showPlans();
                    }

                    @Override
                    public void onError(String message) {
                        scroll.setVisibility(View.GONE);
                        status.setText(getString(R.string.transit_failed) + message);
                    }
                });
    }

    /** 把换乘方案填入列表；点击某条进入详情页。 */
    private void showPlans() {
        container.removeAllViews();
        if (plans.isEmpty()) {
            scroll.setVisibility(View.GONE);
            status.setText(R.string.transit_no_result);
            return;
        }
        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < plans.size(); i++) {
            final int index = i;
            TransitSearch.Plan p = plans.get(i);
            View row = inflater.inflate(R.layout.transit_plan_item, container, false);
            ((TextView) row.findViewById(R.id.plan_title)).setText(buildTitle(p, i));
            ((TextView) row.findViewById(R.id.plan_summary)).setText(p.summary);
            row.setOnClickListener(v -> openDetail(index));
            container.addView(row);
        }
        scroll.setVisibility(View.VISIBLE);
        status.setText(getString(R.string.transit_found_prefix) + plans.size()
                + getString(R.string.transit_found_suffix));
    }

    private String buildTitle(TransitSearch.Plan p, int index) {
        StringBuilder sb = new StringBuilder();
        sb.append(index + 1).append(". 历时 ").append(p.durationMin).append(" 分钟");
        sb.append(" · ").append(String.format(Locale.CHINA, "%.1f", p.distanceM / 1000.0)).append(" 公里");
        if (p.priceCent >= 0) {
            sb.append(" · ¥").append(String.format(Locale.CHINA, "%.2f", p.priceCent / 100.0));
        }
        return sb.toString();
    }

    /** 打开所选方案的详细站点导航页。 */
    private void openDetail(int index) {
        if (index < 0 || index >= plans.size()) {
            return;
        }
        Intent intent = new Intent(this, TransitDetailActivity.class);
        intent.putExtra(AppConst.EXTRA_PLAN, plans.get(index));
        intent.putExtra(AppConst.EXTRA_DEST_NAME, destName);
        startActivity(intent);
    }
}
