package cn.xiaofuge.dsh.diff.service;

import cn.xiaofuge.dsh.diff.model.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diff Narrator 代码变更叙事器：
 * - 加载 unified diff 文本，按文件分组解析 hunk
 * - 人话变更说明：新增/删除/修改文件分类，按目录聚合
 * - 风险扫描：删测试、改配置、改 SQL/迁移、删校验、权限/鉴权改动等
 * - 测试缺口：改了 src/main 但没改 src/test 的模块
 * - 回滚建议：按风险度给出回滚单元
 * 红线：不改代码、不执行 git 写命令。
 */
@Service
public class DiffNarratorService {

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+?) b/(.+?)$");
    private static final Pattern HUNK = Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s*@@");

    public Map<String, Object> loadRange(String diffText) {
        if (diffText == null || diffText.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "diffText 不能为空");
        }
        List<Map<String, Object>> files = parseFiles(diffText);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fileCount", files.size());
        r.put("files", files.stream().map(f -> Map.of(
                "path", f.get("path"),
                "changeType", f.get("changeType"),
                "added", f.get("added"),
                "deleted", f.get("deleted"))).toList());
        return r;
    }

    public Map<String, Object> summarizeFiles(String diffText) {
        List<Map<String, Object>> files = parseFiles(diffText);
        int totalAdd = 0, totalDel = 0;
        Map<String, int[]> byDir = new LinkedHashMap<>();
        List<String> newFiles = new ArrayList<>(), deletedFiles = new ArrayList<>(), renamed = new ArrayList<>();
        List<Map<String, Object>> hotFiles = new ArrayList<>();
        for (Map<String, Object> f : files) {
            int a = (int) f.get("added"), d = (int) f.get("deleted");
            totalAdd += a;
            totalDel += d;
            String path = String.valueOf(f.get("path"));
            String type = String.valueOf(f.get("changeType"));
            if ("added".equals(type)) newFiles.add(path);
            if ("deleted".equals(type)) deletedFiles.add(path);
            if ("renamed".equals(type)) renamed.add(path);
            String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "(root)";
            int[] counts = byDir.computeIfAbsent(dir, k -> new int[2]);
            counts[0] += a;
            counts[1] += d;
            hotFiles.add(Map.of("path", path, "added", a, "deleted", d, "changeType", type));
        }
        hotFiles.sort((x, y) -> Integer.compare(
                (int) y.get("added") + (int) y.get("deleted"),
                (int) x.get("added") + (int) x.get("deleted")));
        List<Map<String, Object>> dirs = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byDir.entrySet()) {
            dirs.add(Map.of("dir", e.getKey(), "added", e.getValue()[0], "deleted", e.getValue()[1]));
        }
        dirs.sort((x, y) -> Integer.compare((int) y.get("added") + (int) y.get("deleted"),
                (int) x.get("added") + (int) x.get("deleted")));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fileCount", files.size());
        r.put("totalAdded", totalAdd);
        r.put("totalDeleted", totalDel);
        r.put("newFiles", newFiles);
        r.put("deletedFiles", deletedFiles);
        r.put("renamedFiles", renamed);
        r.put("byDirectory", dirs);
        r.put("hotFiles", hotFiles.stream().limit(8).toList());
        return r;
    }

    public Map<String, Object> riskScan(String diffText) {
        List<Map<String, Object>> files = parseFiles(diffText);
        List<Map<String, Object>> risks = new ArrayList<>();
        for (Map<String, Object> f : files) {
            String path = String.valueOf(f.get("path"));
            String type = String.valueOf(f.get("changeType"));
            @SuppressWarnings("unchecked")
            List<String> removedLines = (List<String>) f.get("removedLines");
            @SuppressWarnings("unchecked")
            List<String> addedLines = (List<String>) f.get("addedLines");

            if (path.contains("src/test/") && "deleted".equals(type)) {
                risks.add(risk(path, "high", "删除了整个测试文件", "确认功能是否同步下线，避免测试静默消失"));
            }
            if (path.matches(".*\\.(yml|yaml|properties|conf|env)$")) {
                risks.add(risk(path, "medium", "修改配置文件", "确认配置变更是否同步到所有环境（dev/staging/prod）"));
            }
            if (path.matches(".*(sql|migration|flyway|liquibase).*") || path.matches(".*db/migration.*")) {
                risks.add(risk(path, "high", "数据库迁移脚本变更", "确认迁移可回滚、锁表风险、执行顺序"));
            }
            String joinedRemoved = String.join("\n", removedLines);
            String joinedAdded = String.join("\n", addedLines);
            if (joinedRemoved.matches("(?s).*(if\\s*\\(|require|assert|validate|check).*") && type.equals("modified")
                    && joinedRemoved.length() > joinedAdded.length() * 2 && !removedLines.isEmpty()) {
                risks.add(risk(path, "medium", "删除行远多于新增行且含校验逻辑", "确认是否误删校验/防御逻辑"));
            }
            if (path.contains("auth") || path.contains("security") || path.contains("permission")
                    || joinedAdded.matches("(?s).*(hasRole|@PreAuthorize|permitAll|authenticated).*")) {
                risks.add(risk(path, "high", "鉴权/权限相关改动", "安全评审：确认权限模型变化是否有越权风险"));
            }
            if (joinedAdded.matches("(?s).*(TODO|FIXME|HACK|XXX).*")) {
                risks.add(risk(path, "low", "新增代码含 TODO/FIXME", "确认遗留项是否进 issue 跟踪"));
            }
            // 疑似硬编码密钥：新增或删除行中出现明文字面量（删除侧命中说明原来就是硬编码，仍值得提醒轮换）
            String joinedAll = joinedAdded + "\n" + joinedRemoved;
            if (joinedAll.matches("(?si).*(password|secret|token|api[_-]?key|passwd)\\s*=\\s*[\"'][^$\\{\\s][^\"']{3,}[\"'].*")) {
                boolean inAdded = joinedAdded.matches("(?si).*(password|secret|token|api[_-]?key|passwd)\\s*=\\s*[\"'][^$\\{\\s][^\"']{3,}[\"'].*");
                risks.add(risk(path, "high",
                        inAdded ? "新增代码疑似硬编码密钥" : "被删除代码中存在硬编码密钥（提醒轮换）",
                        "移除明文密钥，改用密钥管理服务；已泄漏的密钥立即轮换"));
            }
        }
        risks.sort((x, y) -> severityRank(String.valueOf(x.get("severity"))) - severityRank(String.valueOf(y.get("severity"))));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("fileCount", files.size());
        r.put("riskCount", risks.size());
        r.put("risks", risks);
        return r;
    }

    public Map<String, Object> testGap(String diffText) {
        List<Map<String, Object>> files = parseFiles(diffText);
        Set<String> changedMain = new TreeSet<>();
        Set<String> changedTest = new TreeSet<>();
        for (Map<String, Object> f : files) {
            String path = String.valueOf(f.get("path"));
            String type = String.valueOf(f.get("changeType"));
            if ("deleted".equals(type)) continue;
            if (path.contains("src/test/")) {
                changedTest.add(normalizeTestClass(path));
            } else if (path.contains("src/main/") || path.contains("/main/")) {
                changedMain.add(normalizeMainClass(path));
            }
        }
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (String main : changedMain) {
            if (main.isEmpty()) continue;
            boolean covered = changedTest.stream().anyMatch(t -> !t.isEmpty() && (main.endsWith(t) || t.endsWith(main)));
            if (!covered) {
                gaps.add(Map.of("main", main, "hint", "src/main 改动但未发现对应 src/test 改动，建议补充回归用例"));
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("changedMainCount", changedMain.size());
        r.put("changedTestCount", changedTest.size());
        r.put("gapCount", gaps.size());
        r.put("gaps", gaps);
        return r;
    }

    public Map<String, Object> rollbackPlan(String diffText) {
        Map<String, Object> risk = riskScan(diffText);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> risks = (List<Map<String, Object>>) risk.get("risks");
        Map<String, Object> summary = summarizeFiles(diffText);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hotFiles = (List<Map<String, Object>>) summary.get("hotFiles");

        List<Map<String, Object>> units = new ArrayList<>();
        int order = 1;
        // 高风险文件优先单独成回滚单元
        for (Map<String, Object> h : risks) {
            if ("high".equals(h.get("severity"))) {
                units.add(Map.of("order", order++, "scope", "revert 单文件: " + h.get("path"),
                        "reason", h.get("reason"), "risk", "high"));
            }
        }
        // 其余按目录打包
        Set<String> highPaths = risks.stream()
                .filter(x -> "high".equals(x.get("severity")))
                .map(x -> String.valueOf(x.get("path"))).collect(java.util.stream.Collectors.toSet());
        Map<String, Boolean> dirSeen = new LinkedHashMap<>();
        for (Map<String, Object> h : hotFiles) {
            String p = String.valueOf(h.get("path"));
            if (highPaths.contains(p)) continue;
            String dir = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : "(root)";
            if (dirSeen.containsKey(dir)) continue;
            dirSeen.put(dir, true);
            units.add(Map.of("order", order++, "scope", "revert 目录: " + dir, "reason", "常规改动，可按目录整体回滚", "risk", "low"));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("unitCount", units.size());
        r.put("units", units);
        r.put("strategy", "先回滚高风险单文件（配置/迁移/鉴权），再按目录回滚常规改动；每步回滚后跑冒烟。");
        r.put("note", "仅输出回滚计划，不执行任何 git 写命令");
        return r;
    }

    // ---------- helpers ----------

    private List<Map<String, Object>> parseFiles(String diffText) {
        List<Map<String, Object>> files = new ArrayList<>();
        String[] lines = diffText.replace("\r\n", "\n").split("\n", -1);
        Map<String, Object> current = null;
        List<String> added = null, removed = null;
        for (String line : lines) {
            Matcher h = DIFF_HEADER.matcher(line);
            if (h.matches()) {
                if (current != null) {
                    current.put("addedLines", added);
                    current.put("removedLines", removed);
                    files.add(current);
                }
                String bPath = h.group(2);
                current = new LinkedHashMap<>();
                current.put("path", bPath);
                current.put("changeType", "modified");
                current.put("added", 0);
                current.put("deleted", 0);
                added = new ArrayList<>();
                removed = new ArrayList<>();
                continue;
            }
            if (current == null) continue;
            if (line.startsWith("new file mode")) {
                current.put("changeType", "added");
            } else if (line.startsWith("deleted file mode")) {
                current.put("changeType", "deleted");
                current.put("path", current.get("path"));
            } else if (line.startsWith("rename from") || line.startsWith("rename to")) {
                current.put("changeType", "renamed");
            } else if (line.startsWith("--- a/")) {
                current.put("oldPath", line.substring(6));
            } else if (HUNK.matcher(line).matches()) {
                // 新 hunk，重置不了什么，继续
            } else if (line.startsWith("+") && !line.startsWith("+++")) {
                added.add(line.substring(1));
                current.put("added", (int) current.get("added") + 1);
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                removed.add(line.substring(1));
                current.put("deleted", (int) current.get("deleted") + 1);
            }
        }
        if (current != null) {
            current.put("addedLines", added);
            current.put("removedLines", removed);
            files.add(current);
        }
        if (files.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "未能从 diff 文本解析出任何文件，请确认是 git diff / unified diff 格式");
        }
        return files;
    }

    private Map<String, Object> risk(String path, String severity, String reason, String advice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        m.put("severity", severity);
        m.put("reason", reason);
        m.put("advice", advice);
        return m;
    }

    private int severityRank(String s) {
        return switch (s) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
    }

    private String normalizeMainClass(String path) {
        String p = path;
        int i = p.indexOf("src/main/java/");
        if (i >= 0) p = p.substring(i + "src/main/java/".length());
        // 取类名
        int slash = p.lastIndexOf('/');
        String cls = slash >= 0 ? p.substring(slash + 1) : p;
        return cls.replace(".java", "").replace(".kt", "").replace(".scala", "");
    }

    private String normalizeTestClass(String path) {
        String p = path;
        int i = p.indexOf("src/test/java/");
        if (i >= 0) p = p.substring(i + "src/test/java/".length());
        int slash = p.lastIndexOf('/');
        String cls = slash >= 0 ? p.substring(slash + 1) : p;
        return cls.replace(".java", "").replace("Test", "").replace("Tests", "").replace("IT", "");
    }
}
