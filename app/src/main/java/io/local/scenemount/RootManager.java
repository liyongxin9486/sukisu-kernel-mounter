package io.local.scenemount;

import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

final class RootManager {
    static final String HOME = "/data/adb/scene_mount_ui";
    static final class Module {
        String id, name, version;
        boolean disabled, system;
        int mountedApks;
        final List<String> apks = new ArrayList<>();
        String packageName() { return id; }
    }
    static final class Scan {
        final List<Module> modules = new ArrayList<>();
        boolean canUndo;
        Boolean sameBoot;
    }
    static String run(String script) throws Exception {
        Process p = new ProcessBuilder("su", "-c", "nsenter -t 1 -m -- /system/bin/sh")
                .redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = in.read(buffer)) != -1) output.write(buffer, 0, length);
            }
            catch (IOException ignored) { }
        });
        reader.start();
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(("export PATH=/system/bin:/system/xbin:$PATH\n" + script + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!p.waitFor(45, TimeUnit.SECONDS)) {
            // Stop the wait, not an in-flight filesystem transaction. Shell rollback handles errors.
            throw new IOException("操作仍未返回，请稍后刷新核验；不要连续重复挂载。");
        }
        reader.join(2000);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0) throw new IOException(text.trim().isEmpty() ? "Root 操作失败，请检查授权。" : text.trim());
        return text;
    }
    static boolean safe(String value) {
        return value != null && value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") && !value.contains("..");
    }
    static String q(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    static Scan scan() throws Exception {
        String result = run("""
            set -e
            [ "$(id -u)" = 0 ] || exit 1
            boot_id=$(cat /proc/sys/kernel/random/boot_id)
            for d in /data/adb/modules/*; do
              [ -f "$d/module.prop" ] || continue
              grep -qi scene "$d/module.prop" || continue
              id=${d##*/}
              disabled=0
              [ ! -e "$d/disable" ] || disabled=1
              [ ! -e "$d/remove" ] || disabled=1
              printf 'M\t%s\t%s\t%s\n' "$id" "$(base64 "$d/module.prop" | tr -d '\\n\\r')" "$disabled"
              for kind in app priv-app; do
                [ -d "$d/system/$kind" ] || continue
                find "$d/system/$kind" -type f -name '*.apk' | while IFS= read -r f; do
                  relative=${f#"$d/"}
                  target=/${relative}
                  visible=0
                  if [ -f "$target" ] && cmp -s "$f" "$target"; then visible=1; fi
                  printf 'A\t%s\t%s\t%s\n' "$id" "$relative" "$visible"
                done
              done
            done
            for s in /data/adb/scene_mount_ui/tx-*; do
              if [ -f "$s/committed" ] && [ "$(cat "$s/boot" 2>/dev/null)" = "$boot_id" ]; then printf 'U\n'; fi
            done
            cmd package list packages -s | while IFS= read -r p; do
              case "$p" in package:*) printf 'P\t%s\n' "${p#package:}" ;; esac
            done
            if [ -f /data/adb/scene_mount_ui/before_soft_restart ]; then
              printf 'R\t%s\t%s\n' "$(cat /data/adb/scene_mount_ui/before_soft_restart)" "$(cat /proc/sys/kernel/random/boot_id)"
            fi
            """);
        Scan scan = new Scan();
        Map<String, Module> map = new LinkedHashMap<>();
        Set<String> systemPackages = new HashSet<>();
        for (String line : result.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length == 4 && f[0].equals("M") && safe(f[1])) {
                Properties props = new Properties();
                props.load(new StringReader(new String(Base64.decode(f[2], Base64.DEFAULT), StandardCharsets.UTF_8)));
                // A Scene reference in an unrelated property is insufficient.
                String origin = props.getProperty("author", "") + " " + props.getProperty("description", "");
                if (!origin.toLowerCase(Locale.ROOT).contains("scene")) continue;
                Module m = new Module();
                m.id = f[1]; m.name = props.getProperty("name", m.id);
                m.version = props.getProperty("version", ""); m.disabled = f[3].equals("1");
                map.put(m.id, m);
            } else if (f.length == 4 && f[0].equals("A") && map.containsKey(f[1])) {
                String[] path = f[2].split("/");
                if (path.length != 4 || !path[0].equals("system") ||
                        !(path[1].equals("app") || path[1].equals("priv-app")) ||
                        !safe(path[2]) || !safe(path[3]) || !path[3].endsWith(".apk")) continue;
                Module m = map.get(f[1]);
                m.apks.add(f[2]);
                if (f[3].equals("1")) m.mountedApks++;
            } else if (line.equals("U")) scan.canUndo = true;
            else if (f.length == 3 && f[0].equals("R")) scan.sameBoot = f[1].equals(f[2]);
            else if (f.length == 2 && f[0].equals("P")) systemPackages.add(f[1]);
        }
        for (Module m : map.values()) if (!m.apks.isEmpty()) {
            m.system = systemPackages.contains(m.id);
            scan.modules.add(m);
        }
        scan.modules.sort(Comparator.comparing(m -> m.name));
        return scan;
    }

    static String mount(List<Module> modules) throws Exception {
        if (modules.isEmpty()) throw new IOException("请先选择应用。");
        String token = "tx-" + System.currentTimeMillis();
        String stage = "/mnt/scene_mount_ui/" + token;
        String state = HOME + "/" + token;
        Set<String> targets = new LinkedHashSet<>();
        Map<String, Set<String>> folders = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        StringBuilder copies = new StringBuilder();
        for (Module m : modules) {
            if (!safe(m.id) || m.disabled) throw new IOException("模块已禁用或无效：" + m.id);
            for (String relative : m.apks) {
                String[] parts = relative.split("/");
                if (parts.length != 4 || !safe(parts[2]) || !safe(parts[3])) throw new IOException("APK 路径无效。");
                if (!seen.add(relative)) throw new IOException("多个模块包含同一路径，请分别选择：" + relative);
                String source = "/data/adb/modules/" + m.id + "/" + relative;
                String destination = stage + "/lower/" + parts[1] + "/" + parts[2] + "/" + parts[3];
                targets.add(parts[1]);
                folders.computeIfAbsent(parts[1], key -> new LinkedHashSet<>()).add(parts[2]);
                copies.append("[ ! -L ").append(q(source)).append(" ] || exit 1\n")
                        .append("[ -f ").append(q(source)).append(" ] || exit 1\n")
                        .append("mkdir -p ").append(q(stage + "/lower/" + parts[1] + "/" + parts[2])).append("\n")
                        .append("cp ").append(q(source)).append(' ').append(q(destination)).append("\n")
                        .append("chmod 0644 ").append(q(destination)).append("\n")
                        .append("cmp -s ").append(q(source)).append(' ').append(q(destination)).append("\n");
            }
        }
        StringBuilder s = new StringBuilder("set -e\numask 022\n");
        s.append("stage=").append(q(stage)).append("\nstate=").append(q(state)).append("\n");
        s.append("""
            mkdir -p /data/adb/scene_mount_ui /mnt/scene_mount_ui "$stage" "$state"
            chmod 0700 /data/adb/scene_mount_ui "$state"
            : > "$state/bound"
            cleanup() {
              rc=$?
              if [ "$rc" != 0 ]; then
                while IFS= read -r kind; do
                  umount -l "/system/$kind" || true
                done < "$state/bound"
                for kind in app priv-app; do umount -l "$stage/view/$kind" 2>/dev/null || true; done
                umount -l "$stage" 2>/dev/null || true
                printf '挂载失败，已尝试回滚本次变更。\n'
              fi
            }
            trap cleanup EXIT
            mount -t tmpfs -o mode=0755 tmpfs "$stage"
            mkdir -p "$stage/lower" "$stage/view"
            """);
        s.append(copies);
        s.append("chown -R 0:0 \"$stage/lower\"\nfind \"$stage/lower\" -type d -exec chmod 0755 {} \\;\nchcon -R u:object_r:system_file:s0 \"$stage/lower\"\n");
        // Existing app directories can be replaced individually, without adding overlay depth.
        for (String kind : targets) {
            s.append("direct_").append(kind.replace('-', '_')).append("=1\n");
            for (String folder : folders.get(kind)) s.append("[ -d ").append(q("/system/" + kind + "/" + folder))
                    .append(" ] || direct_").append(kind.replace('-', '_')).append("=0\n");
            s.append("if [ \"$direct_").append(kind.replace('-', '_')).append("\" = 0 ]; then\n")
                    .append("mkdir -p \"$stage/view/").append(kind).append("\"\n")
                    .append("mount -t overlay overlay -o \"ro,lowerdir=$stage/lower/").append(kind)
                    .append(":/system/").append(kind).append("\" \"$stage/view/").append(kind).append("\"\nfi\n");
        }
        for (String kind : targets) {
            s.append("if [ \"$direct_").append(kind.replace('-', '_')).append("\" = 1 ]; then\n");
            for (String folder : folders.get(kind)) appendBind(s, "\"$stage/lower/" + kind + "/" + folder + "\"", kind + "/" + folder);
            s.append("else\n");
            appendBind(s, "\"$stage/view/" + kind + "\"", kind);
            s.append("fi\n");
        }
        s.append("printf '%s\\n' ").append(q(stage)).append(" > \"$state/stage\"\n");
        for (Module m : modules) s.append("printf '%s\\n' ").append(q(m.id)).append(" >> \"$state/modules\"\n");
        s.append("cat /proc/sys/kernel/random/boot_id > \"$state/boot\"\ntouch \"$state/committed\"\nprintf '挂载完成。请单独点击框架软重启，使包管理器重新扫描。\\n'\n");
        return run(s.toString());
    }

    private static void appendBind(StringBuilder s, String sourceExpression, String relative) {
        s.append("mkdir -p \"$state/").append(relative.contains("/") ? relative.substring(0, relative.lastIndexOf('/')) : ".").append("\"\n")
                .append("mount --bind ").append(sourceExpression).append(' ').append(q("/system/" + relative)).append("\n")
                .append("printf '%s\\n' ").append(q(relative)).append(" >> \"$state/bound\"\n")
                .append("awk -v p=").append(q("/system/" + relative))
                .append(" '$5==p{id=$1} END{print id}' /proc/self/mountinfo > \"$state/").append(relative).append(".id\"\n");
    }

    static String unmount(List<Module> modules) throws Exception {
        if (modules.isEmpty()) throw new IOException("请先选择已挂载的应用。");
        String token = "tx-" + System.currentTimeMillis();
        String stage = "/mnt/scene_mount_ui/" + token;
        String state = HOME + "/" + token;
        Set<String> directories = new LinkedHashSet<>();
        StringBuilder s = new StringBuilder("set -e\numask 022\n");
        // Before removing a system scan path, require a preserved user installation.
        for (Module m : modules) {
            if (!safe(m.id)) throw new IOException("模块 ID 无效。");
            s.append("data_path=$(cmd package path ").append(q(m.id)).append(" | sed -n 's/^package://p' | head -n 1)\n")
                    .append("case \"$data_path\" in /data/app/*) [ -f \"$data_path\" ] ;; *) printf '%s\\n' ")
                    .append(q(m.name + "：未找到保留在 /data/app 的普通安装。请先覆盖安装原 APK，再取消挂载。"))
                    .append("; exit 1 ;; esac\n");
            for (String relative : m.apks) {
                String[] p = relative.split("/");
                if (p.length != 4 || !p[0].equals("system") || !(p[1].equals("app") || p[1].equals("priv-app"))
                        || !safe(p[2]) || !safe(p[3])) throw new IOException("APK 路径无效。");
                directories.add(p[1] + "/" + p[2]);
                String target = "/" + relative;
                s.append("if [ -f ").append(q(target)).append(" ]; then cmp -s ")
                        .append(q("/data/adb/modules/" + m.id + "/" + relative)).append(' ').append(q(target))
                        .append(" || { printf '%s\\n' ").append(q(m.name + "：系统 APK 与 Scene 模块不同，已停止以免影响其他版本。"))
                        .append("; exit 1; }; fi\n");
            }
        }
        s.append("stage=").append(q(stage)).append("\nstate=").append(q(state)).append("\n")
                .append("""
                    mkdir -p /data/adb/scene_mount_ui /mnt/scene_mount_ui "$stage" "$state"
                    chmod 0700 /data/adb/scene_mount_ui "$state"
                    : > "$state/bound"
                    cleanup() {
                      rc=$?
                      if [ "$rc" != 0 ]; then
                        while IFS= read -r relative; do umount -l "/system/$relative" || true; done < "$state/bound"
                        umount -l "$stage" 2>/dev/null || true
                        printf '取消挂载失败，已尝试回滚本次变更。\n'
                      fi
                    }
                    trap cleanup EXIT
                    mount -t tmpfs -o mode=0755 tmpfs "$stage"
                    """);
        for (String directory : directories) {
            s.append("[ -d ").append(q("/system/" + directory)).append(" ] || exit 1\n")
                    .append("mkdir -p \"$stage/empty/").append(directory).append("\"\n");
        }
        s.append("chcon -R u:object_r:system_file:s0 \"$stage/empty\"\n");
        // Empty per-app bind views suppress every underlying layer, including older Scene mounts.
        for (String directory : directories) appendBind(s, "\"$stage/empty/" + directory + "\"", directory);
        for (Module m : modules) {
            for (String relative : m.apks) s.append("[ ! -e ").append(q("/" + relative)).append(" ] || exit 1\n");
            s.append("printf '%s\\n' ").append(q(m.id)).append(" >> \"$state/modules\"\n");
        }
        s.append("printf '%s\\n' \"$stage\" > \"$state/stage\"\n")
                .append("cat /proc/sys/kernel/random/boot_id > \"$state/boot\"\n")
                .append("printf 'unmount\\n' > \"$state/operation\"\ntouch \"$state/committed\"\n")
                .append("printf '已取消所选应用的系统目录映射。普通安装和数据保留；请框架软重启，核验是否回退为普通应用。\\n'\n");
        return run(s.toString());
    }

    static String undo() throws Exception {
        return run("""
            set -e
            state=
            boot_id=$(cat /proc/sys/kernel/random/boot_id)
            for s in /data/adb/scene_mount_ui/tx-*; do
              if [ -f "$s/committed" ] && [ "$(cat "$s/boot" 2>/dev/null)" = "$boot_id" ]; then state=$s; fi
            done
            [ -n "$state" ] || { printf '没有可撤销的挂载。\n'; exit 1; }
            stage=$(cat "$state/stage")
            case "$stage" in /mnt/scene_mount_ui/tx-[0-9]*) ;; *) exit 1 ;; esac
            # Verify ownership for all targets before unmounting any of them.
            while IFS= read -r kind; do
              case "$kind" in
                app|priv-app) ;;
                app/*|priv-app/*)
                  folder=${kind#*/}
                  case "$folder" in ''|*[!A-Za-z0-9_.-]*|*..*) exit 1 ;; esac ;;
                *) exit 1 ;;
              esac
              expected=$(cat "$state/$kind.id")
              actual=$(awk -v p="/system/$kind" '$5==p{id=$1} END{print id}' /proc/self/mountinfo)
              [ "$expected" = "$actual" ] || { printf '目录挂载已变化，未撤销。请勿覆盖其他工具的挂载。\n'; exit 1; }
            done < "$state/bound"
            while IFS= read -r kind; do umount -l "/system/$kind"; done < "$state/bound"
            rm "$state/committed"
            for kind in app priv-app; do umount -l "$stage/view/$kind" 2>/dev/null || true; done
            umount -l "$stage"
            printf '已撤销最近一次操作。若此前已软重启，请再次软重启更新应用身份。\n'
            """);
    }
    static String softRestart() throws Exception {
        return run("""
            set -e
            [ "$(getprop init.svc.zygote)" = running ] || { printf '主 Zygote 未运行，已停止操作。\n'; exit 1; }
            mkdir -p /data/adb/scene_mount_ui
            chmod 0700 /data/adb/scene_mount_ui
            cat /proc/sys/kernel/random/boot_id > /data/adb/scene_mount_ui/before_soft_restart
            setprop ctl.restart zygote
            """);
    }
}
