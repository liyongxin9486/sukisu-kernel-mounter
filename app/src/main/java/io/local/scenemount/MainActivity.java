package io.local.scenemount;

import android.app.*;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(245, 247, 250), INK = Color.rgb(28, 42, 57),
            MUTED = Color.rgb(95, 110, 125), BLUE = Color.rgb(37, 91, 181);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<RootManager.Module> modules = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private LinearLayout list;
    private TextView status, log, selection;
    private Button refresh, all, mount, unmount, undo, restart;
    private boolean busy, canUndo, offerRestartAfterLoad;
    private String lastLog = "挂载只在当前内核运行期间有效；完整重启后需重新挂载。";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(16), dp(22), dp(28));
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        root.addView(text("SUKISU · ROOT TOOL", 12, BLUE, true));
        TextView title = text(getString(R.string.app_name), 26, INK, true);
        margin(root, title, 8, 6);
        margin(root, text("系统应用挂载与回退管理", 15, MUTED, false), 0, 18);
        status = text("正在检查 Root 和转换模块…", 14, INK, false);
        status.setPadding(dp(15), dp(13), dp(15), dp(13));
        status.setBackground(box(Color.rgb(229, 238, 252), 14));
        margin(root, status, 0, 18);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView section = text("Scene 转换记录", 18, INK, true);
        header.addView(section, new LinearLayout.LayoutParams(0, -2, 1));
        refresh = smallButton("刷新"); header.addView(refresh);
        all = smallButton("全选"); header.addView(all);
        margin(root, header, 0, 8);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        selection = text("选择要操作的应用", 13, MUTED, false);
        margin(root, selection, 5, 12);

        mount = actionButton("挂载选中应用", true);
        margin(root, mount, 0, 8);
        unmount = actionButton("取消选中挂载", false);
        margin(root, unmount, 0, 8);
        restart = actionButton("框架软重启", false);
        margin(root, restart, 0, 8);
        undo = actionButton("撤销最近一次操作", false);
        margin(root, undo, 0, 18);
        margin(root, text("操作结果", 16, INK, true), 0, 8);
        log = text(lastLog, 13, MUTED, false);
        log.setTextIsSelectable(true);
        log.setPadding(dp(15), dp(14), dp(15), dp(14));
        log.setBackground(box(Color.WHITE, 14));
        root.addView(log);
        margin(root, text("挂载后软重启，识别为系统应用；取消挂载后软重启，核验回退为普通应用。\n\n取消挂载会保留 Scene 模块、普通安装和数据。撤销可恢复最近一次操作。priv-app 权限白名单仍需由模块提供。", 12, MUTED, false), 16, 0);

        refresh.setOnClickListener(v -> load());
        all.setOnClickListener(v -> {
            boolean every = modules.stream().filter(m -> !m.disabled || m.mountedApks > 0).allMatch(m -> selected.contains(m.id));
            selected.clear();
            if (!every) for (RootManager.Module m : modules) if (!m.disabled || m.mountedApks > 0) selected.add(m.id);
            render();
        });
        unmount.setOnClickListener(v -> {
            List<RootManager.Module> chosen = new ArrayList<>();
            for (RootManager.Module m : modules) if (selected.contains(m.id) && m.mountedApks > 0) chosen.add(m);
            if (chosen.isEmpty()) return;
            new AlertDialog.Builder(this).setTitle("取消选中挂载")
                    .setMessage("将取消 " + chosen.size() + " 个应用的系统目录映射。确认保留普通安装后才会操作，不删除模块和数据。\n\n完成后需要框架软重启，才能回退并核验普通应用身份。")
                    .setNegativeButton("返回", null).setPositiveButton("取消挂载", (d, w) ->
                            task("正在取消挂载…", () -> RootManager.unmount(chosen), true)).show();
        });
        mount.setOnClickListener(v -> {
            List<RootManager.Module> chosen = new ArrayList<>();
            for (RootManager.Module m : modules) if (selected.contains(m.id) && !m.disabled) chosen.add(m);
            if (chosen.isEmpty()) return;
            boolean privileged = chosen.stream().anyMatch(m -> m.apks.stream().anyMatch(p -> p.startsWith("system/priv-app/")));
            String msg = "将挂载 " + chosen.size() + " 个应用，保留原应用数据。挂载后需单独软重启框架。";
            if (privileged) msg += "\n\n包含 priv-app：此工具仅挂载 APK，不会自动配置特权权限白名单。";
            new AlertDialog.Builder(this).setTitle("挂载选中应用").setMessage(msg)
                    .setNegativeButton("取消", null).setPositiveButton("挂载", (d, w) ->
                            task("正在挂载…", () -> RootManager.mount(chosen), true)).show();
        });
        restart.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("仅重启 Android 框架")
                .setMessage("当前应用会关闭，桌面短暂重新加载。此操作只重启 Zygote，不重启内核。请先保存正在编辑的内容。")
                .setNegativeButton("取消", null).setPositiveButton("软重启", (d, w) -> {
                    log.setText("正在请求框架软重启…");
                    task("正在请求软重启…", RootManager::softRestart, false);
                }).show());
        undo.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("撤销最近一次操作")
                .setMessage("撤销本工具最近一次成功的挂载或取消挂载。若已软重启，撤销后需再软重启一次才能更新应用身份。")
                .setNegativeButton("返回", null).setPositiveButton("撤销操作", (d, w) ->
                        task("正在撤销操作…", RootManager::undo, false)).show());
        load();
    }
    interface Operation { String call() throws Exception; }
    private void task(String message, Operation operation, boolean offerRestart) {
        if (busy) return;
        setBusy(true); status.setText(message);
        worker.submit(() -> {
            try {
                String result = operation.call();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    lastLog = result.trim().isEmpty() ? "操作已提交。" : result.trim();
                    log.setText(lastLog); offerRestartAfterLoad = offerRestart; setBusy(false); load();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); status.setText("操作未完成"); lastLog = e.getMessage(); log.setText(lastLog); } });
            }
        });
    }
    private void load() {
        if (busy) return;
        setBusy(true); status.setText(R.string.reading);
        worker.submit(() -> {
            try {
                RootManager.Scan scan = RootManager.scan();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    modules.clear(); modules.addAll(scan.modules); canUndo = scan.canUndo;
                    Set<String> enabled = new HashSet<>();
                    for (RootManager.Module m : modules) if (!m.disabled || m.mountedApks > 0) enabled.add(m.id);
                    selected.retainAll(enabled);
                    setBusy(false); status.setText(getString(R.string.root_ready, modules.size())); render();
                    if (scan.sameBoot != null && lastLog.startsWith("挂载只在")) {
                        lastLog = scan.sameBoot ? "上次软重启核验：内核启动 ID 未变。" : "检测到内核已经重新启动，请重新核验挂载状态。";
                        log.setText(lastLog);
                    }
                    if (offerRestartAfterLoad) {
                        offerRestartAfterLoad = false;
                        new AlertDialog.Builder(this).setTitle("操作成功")
                                .setMessage("现在可以软重启框架，让系统重新识别应用身份。")
                                .setNegativeButton("稍后", null).setPositiveButton("框架软重启", (d, w) -> restart.performClick()).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    modules.clear(); canUndo = false; offerRestartAfterLoad = false; setBusy(false); render();
                    status.setText(R.string.root_failed);
                    log.setText(e.getMessage());
                });
            }
        });
    }
    private void render() {
        list.removeAllViews();
        if (modules.isEmpty()) {
            TextView empty = text("还没有可挂载的 Scene 转换模块\n\n在 Scene 中长按应用，选择“转为系统应用”，勾选“创建 Magisk 模块”，完成后回到这里刷新。", 14, MUTED, false);
            empty.setPadding(dp(18), dp(20), dp(18), dp(20)); empty.setBackground(box(Color.WHITE, 16));
            margin(list, empty, 0, 10);
        }
        for (RootManager.Module m : modules) {
            LinearLayout card = new LinearLayout(this); card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(10), dp(13), dp(12), dp(13)); card.setBackground(box(Color.WHITE, 16));
            CheckBox check = new CheckBox(this); check.setButtonTintList(android.content.res.ColorStateList.valueOf(BLUE));
            check.setChecked(selected.contains(m.id)); check.setEnabled(!busy && (!m.disabled || m.mountedApks > 0));
            card.addView(check);
            LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(5), 0, 0, 0);
            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(text(m.name, 17, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            boolean mounted = m.mountedApks == m.apks.size();
            boolean partial = m.mountedApks > 0 && !mounted;
            int badgeColor = mounted ? Color.rgb(24, 117, 75) : partial ? Color.rgb(160, 94, 12) : MUTED;
            int badgeBackground = mounted ? Color.rgb(226, 245, 234) : partial ? Color.rgb(255, 241, 216) : Color.rgb(237, 240, 244);
            TextView badge = text(mounted ? "已挂载" : partial ? "部分挂载" : "未挂载", 12, badgeColor, true);
            badge.setPadding(dp(10), dp(3), dp(10), dp(3));
            badge.setBackground(box(badgeBackground, 8));
            titleRow.addView(badge);
            info.addView(titleRow);
            info.addView(text(m.id, 12, MUTED, false));
            String state = m.system ? (m.mountedApks == 0 ? "待软重启回退普通应用" : "系统应用")
                    : mounted ? "待软重启识别系统身份" : "普通应用";
            if (m.disabled) state += " · 模块已禁用 / 待删除";
            TextView detail = text(state + "\nAPK 挂载：" + m.mountedApks + "/" + m.apks.size(), 12, MUTED, false);
            detail.setPadding(0, dp(6), 0, 0); info.addView(detail);
            card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            check.setOnCheckedChangeListener((b, checked) -> { if (checked) selected.add(m.id); else selected.remove(m.id); updateButtons(); });
            card.setOnClickListener(v -> { if (check.isEnabled()) check.setChecked(!check.isChecked()); });
            margin(list, card, 0, 10);
        }
        updateButtons();
    }
    private void setBusy(boolean value) { busy = value; updateButtons(); }
    private void updateButtons() {
        if (mount == null) return;
        mount.setEnabled(!busy && modules.stream().anyMatch(m -> selected.contains(m.id) && !m.disabled));
        unmount.setEnabled(!busy && modules.stream().anyMatch(m -> selected.contains(m.id) && m.mountedApks > 0));
        refresh.setEnabled(!busy); all.setEnabled(!busy && !modules.isEmpty());
        undo.setEnabled(!busy && canUndo); restart.setEnabled(!busy && !modules.isEmpty());
        mount.setAlpha(mount.isEnabled() ? 1f : .42f);
        unmount.setAlpha(unmount.isEnabled() ? 1f : .42f);
        undo.setAlpha(undo.isEnabled() ? 1f : .42f);
        restart.setAlpha(restart.isEnabled() ? 1f : .42f);
        selection.setText(selected.isEmpty() ? "选择要操作的应用" : "已选择 " + selected.size() + " 个应用");
        for (int i = 0; i < list.getChildCount(); i++) list.getChildAt(i).setEnabled(!busy);
    }
    private int dp(int n) { return Math.round(getResources().getDisplayMetrics().density * n); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setLineSpacing(dp(3), 1); if (bold) t.setTypeface(null, Typeface.BOLD); return t;
    }
    private GradientDrawable box(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private Button smallButton(String title) {
        Button b = new Button(this); b.setText(title); b.setTextSize(13); b.setAllCaps(false); b.setTextColor(BLUE);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(12), 0, dp(12), 0); return b;
    }
    private Button actionButton(String title, boolean primary) {
        Button b = new Button(this); b.setText(title); b.setTextSize(15); b.setAllCaps(false);
        b.setTextColor(primary ? Color.WHITE : BLUE); b.setBackground(box(primary ? BLUE : Color.rgb(230, 237, 248), 13));
        b.setPadding(dp(12), dp(13), dp(12), dp(13)); b.setMinimumHeight(dp(52)); return b;
    }
    private void margin(LinearLayout parent, View child, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); p.bottomMargin = dp(bottom); parent.addView(child, p);
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
