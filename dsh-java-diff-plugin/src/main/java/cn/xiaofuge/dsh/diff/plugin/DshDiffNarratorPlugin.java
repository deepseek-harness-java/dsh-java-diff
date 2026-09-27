package cn.xiaofuge.dsh.diff.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshDiffNarratorPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-diff-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshDiffNarratorPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new LoadRangeTool(),
                new SummarizeTool(),
                new RiskScanTool(),
                new TestGapTool(),
                new RollbackPlanTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("diff-diffnarrator-capabilities", 20, """
                ## DSH DiffNarrator Plugin
                - 全部 5 个工具只读：解析 diff、摘要、风险、测试缺口、回滚计划，不修改代码。
                - 红线：绝不执行 git 写命令（add/commit/push/revert/checkout/reset），也不生成让用户直接复制执行的 git 写命令；用户要求回滚时只给 rollback 计划文本。
                - 风险扫描基于启发式规则（路径/关键字），high 级风险必须给出确认建议而非断言。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__diff_")) {
                return PluginHookResult.context("DSH DiffNarrator audit: read-only diff analysis, never writes code or runs git commands.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_DIFF_ADMIN_URL", "http://127.0.0.1:8098")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String diffPayload(Map<String, Object> args) {
        return "{\"diffText\":\"" + json(str(args, "diffText")) + "\"}";
    }

    private abstract class DiffToolBase extends AbstractTool {
        @Override public boolean isConcurrencySafe(Object args) { return true; }

        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post(path(), diffPayload(args), args));
        }

        abstract String path();
    }

    private class LoadRangeTool extends DiffToolBase {
        @Override public String name() { return "diff_load_range"; }
        @Override public String description() { return "加载并分组 git diff：按文件归组，统计每个文件新增/删除行数与变更类型（added/deleted/modified/renamed）。分析变更范围时先调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("diffText", stringSchema("git diff / unified diff 全文文本"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8098"))
                    .required("diffText")
                    .build();
        }
        @Override String path() { return "/api/diff/load"; }
    }

    private class SummarizeTool extends DiffToolBase {
        @Override public String name() { return "diff_summarize_files"; }
        @Override public String description() { return "变更摘要：目录聚合统计、热点文件 TOP、新文件/删除文件清单。生成人话变更说明时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("diffText", stringSchema("git diff / unified diff 全文文本"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8098"))
                    .required("diffText")
                    .build();
        }
        @Override String path() { return "/api/diff/summarize"; }
    }

    private class RiskScanTool extends DiffToolBase {
        @Override public String name() { return "diff_risk_scan"; }
        @Override public String description() { return "风险扫描（启发式）：删除测试、改配置、数据库迁移、鉴权改动、疑似硬编码密钥、TODO 遗留，按 severity 分级。变更评审时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("diffText", stringSchema("git diff / unified diff 全文文本"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8098"))
                    .required("diffText")
                    .build();
        }
        @Override String path() { return "/api/diff/risks"; }
    }

    private class TestGapTool extends DiffToolBase {
        @Override public String name() { return "diff_test_gap"; }
        @Override public String description() { return "测试缺口：改了 src/main 但没有对应 src/test 改动的类清单。判断是否需要补测试时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("diffText", stringSchema("git diff / unified diff 全文文本"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8098"))
                    .required("diffText")
                    .build();
        }
        @Override String path() { return "/api/diff/test-gap"; }
    }

    private class RollbackPlanTool extends DiffToolBase {
        @Override public String name() { return "diff_rollback_plan"; }
        @Override public String description() { return "回滚计划：高风险文件单独 revert 单元 + 其余按目录打包，含执行顺序与策略。只输出计划文本，不执行 git 命令。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("diffText", stringSchema("git diff / unified diff 全文文本"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8098"))
                    .required("diffText")
                    .build();
        }
        @Override String path() { return "/api/diff/rollback-plan"; }
    }
}
