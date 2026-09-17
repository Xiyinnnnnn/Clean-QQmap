package com.xiyin.navi;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.xiyin.navi.core.KeyManager;

/**
 * 首次启动的 Key 鉴权页（v2.9 新增，为开源发布设计）。
 *
 * <p>流程：启动 → 无有效 Key 则进入本页 → 输入并校验 → 通过后保存并进入地图。
 * 若 Manifest 中已内置 Key（开发者自用场景），会直接跳过本页。
 *
 * <p>校验说明：此处验证的是「Key 本身是否有效」；
 * 腾讯还要求 Key 绑定「包名 + 签名 SHA1」，那部分需使用者在腾讯控制台自行配置。
 */
public class KeySetupActivity extends Activity {

    /** 由 MainActivity 传入：Key 失效时也复用本页重新输入 */
    public static final String EXTRA_REASON = "extra_reason";

    /**
     * 显式要求重新配置（用户主动点「更换 Key」时传入）。
     *
     * <p>为什么需要它：不能简单地"有 Key 就跳过本页"，否则用户主动点「更换 Key」
     * 会被立刻弹回去，无法修改。只有<b>自动启动</b>（无此标记）才做跳过判断。
     */
    public static final String EXTRA_FORCE_SETUP = "extra_force_setup";

    private EditText input;
    private TextView status;
    private Button confirm;
    private String pendingKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean forceSetup = getIntent().getBooleanExtra(EXTRA_FORCE_SETUP, false);
        String existing = KeyManager.getEffectiveKey(this);
        // 自动启动（非用户主动更换）且已有可用 Key → 跳过本页直接进地图
        if (!forceSetup && !TextUtils.isEmpty(existing)) {
            KeyManager.applyKey(getApplicationContext(), existing);
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_key_setup);

        input = findViewById(R.id.key_input);
        status = findViewById(R.id.key_status);
        confirm = findViewById(R.id.key_confirm);

        String reason = getIntent().getStringExtra(EXTRA_REASON);
        if (!TextUtils.isEmpty(reason)) {
            status.setText(reason);
        } else {
            status.setText(R.string.key_hint);
        }

        // 若已保存过 Key，预填便于修改
        String saved = KeyManager.getSavedKey(this);
        if (!TextUtils.isEmpty(saved)) {
            input.setText(saved);
            input.setSelection(saved.length());
        }

        confirm.setOnClickListener(v -> doVerify());
    }

    private void doVerify() {
        final String key = input.getText() == null ? "" : input.getText().toString().trim();
        if (key.length() < 10) {
            status.setText(R.string.key_too_short);
            return;
        }
        pendingKey = key;
        confirm.setEnabled(false);
        status.setText(R.string.key_verifying);

        KeyManager.verifyKey(this, key, (ok, message) -> {
            confirm.setEnabled(true);
            status.setText(message);
            if (!ok) {
                return;
            }
            // 校验通过：保存并用官方入口注入 SDK，然后进地图
            KeyManager.saveKey(this, pendingKey);
            KeyManager.applyKey(getApplicationContext(), pendingKey);
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        if (getIntent().getBooleanExtra(EXTRA_FORCE_SETUP, false)) {
            // 用户主动点「更换 Key」进来的：返回就是放弃修改，回到地图
            finish();
            return;
        }
        // 首次启动且尚无 Key：没有 Key 无法使用，直接退出应用
        finishAffinity();
    }
}
