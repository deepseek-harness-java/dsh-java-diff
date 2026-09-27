# DSH Diff Narrator（代码变更叙事器）

> DSH Java Native Plugin 独立 AI 产品 #7：解析 git diff、生成人话变更说明、风险扫描、测试缺口分析、回滚计划。**不改代码、不执行任何 git 写命令。**

## 概览

- **端口**：8098
- **模块**：`dsh-java-diff-app`（Spring Boot）+ `dsh-java-diff-plugin`（Java Native 插件）
- **插件 ID**：`dsh-java-diff`，入口类 `cn.xiaofuge.dsh.diff.plugin.DshDiffNarratorPlugin`
- **工具前缀**：`diff_`（暴露为 `plugin__dsh-java-diff__<tool>`）

## AI 工具（5 个）

| 工具 | 说明 |
|---|---|
| `diff_load_range(diffText)` | 解析 unified diff，按文件归组（added/deleted/modified/renamed + 增删行数） |
| `diff_summarize_files(diffText)` | 变更摘要：目录聚合、热点文件 TOP、新/删文件清单 |
| `diff_risk_scan(diffText)` | 风险扫描（启发式）：删测试 / 改配置 / DB 迁移 / 鉴权改动 / 硬编码密钥（含删除侧提醒轮换）/ TODO 遗留 |
| `diff_test_gap(diffText)` | 测试缺口：改了 src/main 但无对应 src/test 改动的类 |
| `diff_rollback_plan(diffText)` | 回滚计划：高风险文件单独 revert 单元 + 其余按目录打包，只出计划不执行 |

## REST API（8098）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/diff/load` | `{diffText}` → files[] |
| POST | `/api/diff/summarize` | `{diffText}` → 摘要 + byDirectory + hotFiles |
| POST | `/api/diff/risks` | `{diffText}` → risks[]（severity 分级） |
| POST | `/api/diff/test-gap` | `{diffText}` → gaps[] |
| POST | `/api/diff/rollback-plan` | `{diffText}` → units[] |
| POST | `/api/assistant/stream` | SSE：`{agentId, message}` → 转发 harness |

## 运行

```bash
mvn package -DskipTests
SERVER__PORT= java -jar dsh-java-diff-app/target/dsh-java-diff-app-0.1.0-SNAPSHOT.jar --server.port=8098

cp dsh-java-diff-plugin/target/dsh-java-diff-plugin-0.1.0-SNAPSHOT.jar ~/.dsh/standalone/plugins/dsh-java-diff-plugin.jar
curl -X POST http://127.0.0.1:8090/api/harness/plugins/install -H 'Content-Type: application/json' -d '{
  "pluginId":"dsh-java-diff","jarName":"dsh-java-diff-plugin.jar",
  "displayName":"DiffNarrator 变更叙事器","pluginVersion":"0.1.0","runtimeType":"JAVA_NATIVE",
  "sourcePath":"'"$HOME"'/.dsh/standalone/plugins/dsh-java-diff-plugin.jar",
  "entrypoint":"cn.xiaofuge.dsh.diff.plugin.DshDiffNarratorPlugin","installMode":"REPLACE"}'
curl -X POST http://127.0.0.1:8090/api/harness/plugins/activate -H 'Content-Type: application/json' -d '{"pluginId":"dsh-java-diff"}'
```

## E2E

```bash
python3 diff_narrator_e2e.py   # 8/8 PASS
```

覆盖：diff 分组解析（T0）、变更摘要（T1）、风险扫描（删测试/硬编码密钥/改配置，T2）、测试缺口（T3）、回滚计划高风险优先（T4）、空 diff 400（T5）、**AI 对话真实调用 4 个 diff 工具（T6）**、**AI 安全红线：要求执行 git revert 时拒绝，只给计划（T7）**。

## 安全红线

- **不改代码、不执行 git 写命令**：全部工具只读；AI 被要求直接 revert/commit 时必须拒绝，输出回滚计划文本由人工评估执行。
- 风险扫描为启发式规则（路径/关键字），high 级风险给确认建议而非断言。
- 硬编码密钥检测覆盖大小写（password/PASSWORD/dbPassword），删除侧命中时额外提醒轮换已泄漏密钥。

## 截图

- `screenshots/01-home.png` — 变更分析工作台（摘要 / 风险 / 测试缺口 / 回滚）
- `screenshots/02-ai-drawer.png` — AI 调用工具输出变更叙事 + 回滚计划
- `screenshots/03-safety.png` — 安全红线：拒绝执行 git revert，只给计划文本
