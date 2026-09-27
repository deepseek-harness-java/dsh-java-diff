package cn.xiaofuge.dsh.diff.controller;

import cn.xiaofuge.dsh.diff.config.DiffNarratorProperties;
import cn.xiaofuge.dsh.diff.model.ApiException;
import cn.xiaofuge.dsh.diff.service.DiffNarratorService;
import cn.xiaofuge.dsh.diff.service.HarnessAiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
public class DiffNarratorController {
    private final DiffNarratorService diffService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final DiffNarratorProperties properties;

    public DiffNarratorController(DiffNarratorService diffService,
                                  HarnessAiStreamService harnessAiStreamService,
                                  DiffNarratorProperties properties) {
        this.diffService = diffService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    public record DiffRequest(String diffText) {
    }

    @PostMapping("/api/diff/load")
    public Map<String, Object> load(@RequestBody DiffRequest request) {
        return diffService.loadRange(request.diffText());
    }

    @PostMapping("/api/diff/summarize")
    public Map<String, Object> summarize(@RequestBody DiffRequest request) {
        return diffService.summarizeFiles(request.diffText());
    }

    @PostMapping("/api/diff/risks")
    public Map<String, Object> risks(@RequestBody DiffRequest request) {
        return diffService.riskScan(request.diffText());
    }

    @PostMapping("/api/diff/test-gap")
    public Map<String, Object> testGap(@RequestBody DiffRequest request) {
        return diffService.testGap(request.diffText());
    }

    @PostMapping("/api/diff/rollback-plan")
    public Map<String, Object> rollbackPlan(@RequestBody DiffRequest request) {
        return diffService.rollbackPlan(request.diffText());
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Diff Narrator（代码变更叙事器）：解析 git diff、生成人话变更说明、风险扫描、测试缺口、回滚计划\n");
        prompt.append("- 你可以使用以下工具查证（分析变更时必须先调用工具，禁止凭空概括）：\n");
        prompt.append("  - plugin__dsh-java-diff__diff_load_range(diffText)：加载并分组 diff\n");
        prompt.append("  - plugin__dsh-java-diff__diff_summarize_files(diffText)：变更摘要（目录聚合/热点文件）\n");
        prompt.append("  - plugin__dsh-java-diff__diff_risk_scan(diffText)：风险扫描（删测试/改配置/迁移/鉴权/硬编码密钥）\n");
        prompt.append("  - plugin__dsh-java-diff__diff_test_gap(diffText)：测试缺口（改 main 没 test 的类）\n");
        prompt.append("  - plugin__dsh-java-diff__diff_rollback_plan(diffText)：回滚计划（只出计划）\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行：先用一句话概括这批变更做了什么，再列风险。\n");
        prompt.append("- 风险按 high/medium/low 分级，高风险必须给建议动作。\n");
        prompt.append("- 红线：不修改代码、不执行任何 git 写命令（commit/revert/checkout 等）、只输出分析与计划。\n");
        return prompt.toString();
    }
}
