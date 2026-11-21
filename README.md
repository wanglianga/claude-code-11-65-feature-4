# 旱灾理赔时点特征服务（Agri Point-in-Time Feature Service）

基于 **Java 21 + Spring Boot 3.5 + PostgreSQL 18** 的可本地运行特征服务，用于农业保险旱灾理赔核验。核心原则只有一条：

> **特征取数以「可用时间」而非「观测时间」为准；历史案件保留评分快照；后到数据只能通过受控回填生成复核候选，绝不直接覆盖旧理赔记录。**

本版本新增**回填实验室（Backfill Lab）**：模型团队新增「播种后有效积温」与「灾前植被恢复斜率」两项特征后，平台先区分**可用于历史模型评估的案件**与**只能用于事后补充分析的案件**，再按定义版本分片回填，而不是对过去三年全部案件强行补齐缺失特征。

---

## 1. 两套时间语义

仓库中每条记录都同时保存两个时钟：

| 字段 | 含义 |
| --- | --- |
| `observed_at` | 现象实际发生的时间（影像采集、气象时段、查勘/申报时间） |
| `ingested_at` | 平台真正可以看到这条记录的时间（入库时间） |

修订不是 `UPDATE`，而是**追加新版本**并保留 `revision_of_id` 链。取数时统一使用：

```
ingested_at <= availability_ceiling
```

- 原始评分：`availability_ceiling = scoring_cutoff`
- 受控回填：`availability_ceiling = 修订事件入库时间`，且运行模式为 `CONTROLLED_BACKFILL`，**不写快照**，只生成复核候选。

这样，一景 2024-06-18 采集、2024-06-25 才入库的影像，在 2024-06-22 截止的案件中会被明确排除，并留痕在运行台账里。

---

## 2. 功能模块

| 模块 | 说明 | 主要接口 |
| --- | --- | --- |
| 特征目录 | 维护公式、来源类型、可用时滞（`availability_lag_seconds`）、审批版本；已审批版本不可改公式（数据库触发器 + 服务层双重保护） | `POST /api/catalog/features`、`POST /api/catalog/features/{code}/versions`、`POST .../versions/{v}/approve` |
| 时点数据仓 | 影像 / 气象 / 地块边界 / 播种申报的观测时间、入库时间、修订链 | `POST /api/warehouse/scenes|weather|declarations|parcels/{id}/boundaries` |
| 回填编排器 | 按灾害时点生成地块级特征运行，FIFO 队列 + 租约式认领，可同步重放 | `POST /api/cases/{caseNo}/runs`、`POST /api/runs/{id}/execute`、`GET /api/runs/queue/stats` |
| 穿越检测器 | 核验每个输入在评分截止前是否可用，产出持久化报告与逐条违规 | `GET /api/runs/{id}` → `leakage` |
| 评分快照 | 冻结案件引用的特征值与定义版本，数据库层禁止 UPDATE/DELETE | `GET /api/snapshots/{id}`、`GET /api/cases/{caseNo}/snapshots` |
| 影响分析 | 定位一次数据修订影响哪些案件，受控回填并生成复核候选 | `POST /api/impact/analyses/by-revision/{eventId}`、`GET /api/review/candidates` |
| 案件追溯 / 复现 | 案件运行历史、输入台账，一键按原截止时间重放并比对快照 | `GET /api/cases/{caseNo}/trace`、`POST /api/cases/{caseNo}/reproduce` |
| 结案 | 评分完成后结案（`SCORED → PAID`），结案后任何状态回退都被拒绝 | `POST /api/cases/{caseNo}/settle` |
| 回填实验室 | 特征依赖图、历史可用性扫描、缺失策略库、按定义版本分片的回填调度、不可变实验快照仓与实验对比 | `GET /api/backfill/dependencies`、`POST /api/backfill/scans`、`POST /api/backfill/jobs`、`GET /api/experiments/compare` |

Swagger UI：<http://localhost:8080/swagger-ui.html>　OpenAPI JSON：<http://localhost:8080/v3/api-docs>

---

## 3. 三条强制约束的落地方式

### 3.1 晚到影像不得进入原案件评分

`AsOfSelector.selectSatellite` 分两段查询：

1. `ingested_at <= ceiling` → 可用场景；
2. `ingested_at > ceiling` → 写入台账，判定 `EXCLUDED_LATE_AVAILABILITY`，并产生 `LATE_SCENE_EXCLUDED` 告警。

即使采集时间早于事故、云量更低，也不会进入 `ndvi_mean` 等聚合变量。样例中 `SCENE-2024-06-18-B`（云量 3%，NDVI≈0.21）就是这样被挡在 2024-06-22 截止之外的。

若窗口内所有影像都晚到、导致必填特征无法计算，运行状态为 `INSUFFICIENT_DATA` 或 `LEAKAGE_BLOCKED`，案件转 `UNDER_REVIEW`，**不写快照**。实测：一个缺少可用影像的案件运行得到 `BLOCKED`（`NO_ELIGIBLE_SCENE` 告警），快照数为 0。

### 3.2 两个地块边界版本重叠时按案件锁定版本隔离

每个栅格像元（scene cell）通过 `resolveOwner` 归属到**唯一**一个 `(parcel, boundary_version)`：

- 已被理赔案件锁定的版本优先（越小 case id 越优先）；
- 其次 parcel id、版本号最小的版本；
- 其它覆盖同一像元的版本得到 `EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL`，并产生 `PIXEL_SHARED_BETWEEN_FIELDS` 告警。

归属判断只依赖存储数据，与运行先后顺序无关，因此重放可复现。样例中 P-1001 的 v1 与 v2 共享 5 个像元：锁定 v1 的案件拥有它们，边界变体运行（v2）将其全部排除。

> 边界变体运行使用 `REPLAY` 模式 + `boundary_variant = true`，永不覆盖案件快照。

### 3.3 已结案后的气象修订只生成复核候选

`ImpactService.analyze` 对每个有快照的案件判定：

- 修订在截止前已可用 / 快照实际引用了该版本 → `ALREADY_VISIBLE_AT_CUTOFF`
- 观测窗口与该案件 NDVI/降雨窗口不相交 → `NOT_RELEVANT`
- 否则 → 排队 `CONTROLLED_BACKFILL`（`LATEST_AVAILABLE`，天花板=修订入库时间），运行后可生成 `PENDING_REVIEW` 复核候选，包含 `baseline / candidate / delta`。

**冻结快照不写、已付款结论不动**。样例中 WS-001 的 2024-07-08 修订产生候选（`NDVI_DROUGHT` +0.142，`RAIN_ANOMALY` −9.4），快照哈希保持 `26b972d0…` 不变，案件状态仍为 `PAID`。

`review_candidate` 只允许 `PENDING_REVIEW → ACKNOWLEDGED / DISMISSED`，由理赔人员决定，属于流程动作而非自动改单。

案件状态只允许向前：`OPEN/QUEUED → SCORED → PAID`。受控回填、影响分析、复现失败等路径只会把 `OPEN/QUEUED/SCORED` 提升为 `UNDER_REVIEW`，不会把 `PAID` 降级。

---

## 4. 回填实验室（Backfill Lab）

模型团队新增两项特征，希望回填过去三年案件评估模型效果：

| 特征 | 含义 | 依赖（登记在依赖图） |
| --- | --- | --- |
| `GDD_AFTER_SOWING`（v1 原始 / v2 校准 ×0.85） | 播种后有效积温：基温 10°C，播种日→灾时点逐期累加 | `SOWING_DATE`（播种申报）+ `TEMPERATURE_SERIES`（气象温度，最低覆盖率 0.8） |
| `NDVI_RECOVERY_SLOPE`（v1） | 灾前植被恢复斜率：45 天窗口内各场景平均 NDVI 的最小二乘斜率 | `IMAGERY_SEQUENCE`（影像序列，云量阈值 30%，至少 2 景） |

平台**不**对全部历史案件强行补齐缺失特征，而是先扫描分层、再按版本分片回填：

### 4.1 五个组件

| 组件 | 说明 | 主要接口 |
| --- | --- | --- |
| 特征依赖图 | 登记特征所需输入（播种日期 / 温度序列 / 影像序列）及窗口、云量阈值、最低覆盖率等参数 | `GET/POST /api/backfill/dependencies` |
| 历史可用性扫描器 | 以每个案件自己的评分截止为天花板，逐依赖判定 `AVAILABLE / DEGRADED / LOOKAHEAD_RISK / MISSING`，把案件分为 `EVALUABLE`（可用于历史模型评估）与 `POST_HOC_ONLY`（只能用于事后补充分析） | `POST /api/backfill/scans`、`GET /api/backfill/scans/{id}` |
| 缺失处理策略库 | 版本化策略：云遮挡（v1 `INTERPOLATE` / v2 `EXCLUDE_MISSING`）、迟到申报（`FLAG_LOOKAHEAD_RISK`）、缺测气象（`EXCLUDE_MISSING`） | `GET/POST /api/backfill/policies` |
| 批量回填调度器 | 一个实验对应一个回填作业，按（定义版本 × 案件）分片；默认 `EVALUABLE_ONLY`，`POST_HOC_ONLY` 案件记为 `SKIPPED_SCOPE`，绝不强行补齐 | `POST /api/backfill/jobs`、`POST /api/backfill/jobs/{id}/execute`、`GET /api/backfill/jobs/{id}` |
| 实验快照仓 | 每个分片的结果冻结为不可变实验快照（实验输入、输出、缺失原因、策略应用记录），按（实验， 案件， 定义版本）唯一 | `GET /api/experiments/{id}`、`GET /api/experiments/{id}/snapshots`、`GET /api/experiments/compare` |

### 4.2 三条强制规则

**R2 迟到申报 = 穿越风险，绝不默认采用。** 播种申报的 `ingested_at` 晚于案件截止时，扫描器把该特征的 `SOWING_DATE` 依赖标为 `LOOKAHEAD_RISK`，案件归为 `POST_HOC_ONLY`；调度器默认跳过，不产生实验快照。样例：`HIST-2025-0001` 的申报 2025-06-20 才入库（截止 2025-06-17），GDD 扫描结果为 `LOOKAHEAD_RISK`，原因串注明入库时间晚于截止。

**R3 策略变更 = 新实验，绝不覆盖已报告指标。** 实验创建时冻结策略集合（如 `CLOUD_OCCLUSION` v1 `INTERPOLATE`）；云遮挡策略由插值改为缺失排除（v2 `EXCLUDE_MISSING`）时必须用新实验键回填，重复键返回 `409 EXPERIMENT_EXISTS`。`experiment_snapshot` 由数据库触发器禁止 UPDATE/DELETE。已报告的实验指标（仅统计 `EVALUABLE + COMPUTED` 快照）因此永不被改写。

**R4 并发定义版本 = 分开存储，绝不串用特征值。** 调度器按定义版本分片；同一案件被 v1、v2 两个版本并发回填时，快照仓按（实验， 案件， 定义版本）分别存储，各自携带自己的公式与输入哈希。样例：`EXP-GDD-V1V2` 中 `HIST-2024-0003` 同时得到 v1=1175.998 与 v2=999.598（恰为 ×0.85）两行快照。

### 4.3 样本范围 vs 特征公式的归因

`GET /api/experiments/compare?left=&right=` 对比两个实验：

- `sampleScope`：两侧都可计算的案件数、仅单侧可计算的案件清单、两侧缺失原因直方图 → 指标差异是否来自**样本范围**；
- `valueDiffs`：共同案件上的取值差 → 指标差异是否来自**特征公式或缺失策略**；
- `attribution`：`IDENTICAL` / `SAMPLE_SCOPE` / `FORMULA_OR_POLICY` / `SAMPLE_SCOPE_AND_FORMULA_OR_POLICY`。

实验详情（`GET /api/experiments/{id}`）同时给出**数据可用性说明**（可评估/事后案件数、特征状态分布、缺失原因）。代价是可比较样本变小，收益是实验指标不会混入事故后补录的播种日期，模型团队也能识别结果变化来自样本范围还是特征公式。

---

## 5. 本地启动

### 5.1 直接运行（使用容器内已装运行时）

前置：PostgreSQL 18 已在 `127.0.0.1:5432`，库 `agri_pit`（用户/密码 `ccdev`）。若没有库，先建：

```bash
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d ccdev -c "CREATE DATABASE agri_pit OWNER ccdev;"
```

构建并启动（首次启动自动执行 Flyway 迁移 + 写入演示样例）：

```bash
cd /workspace/65
/opt/maven/bin/mvn -q -DskipTests package
/opt/jdk-25/bin/java -jar target/agri-pit.jar
```

如需清空重来：

```bash
PGPASSWORD=ccdev /opt/postgresql-18/bin/psql -h 127.0.0.1 -U ccdev -d agri_pit -c "drop schema public cascade; create schema public;"
```

数据库连接可用环境变量覆盖：`DB_URL`、`DB_USER`、`DB_PASSWORD`、`SERVER_PORT`、`APP_SEED_DEMO`。

### 5.2 Docker Compose（一键）

```bash
docker compose up --build
```

`app` 等待 `db` 健康后启动，`8080` 端口对外，样例自动写入。停止并清理：`docker compose down -v`。

> 本仓库交付了 `Dockerfile` 与 `docker-compose.yml`；当前隔离容器内没有 Docker 守护进程，因此本 README 的验证结论均来自 5.1 的直接运行方式。Docker 构建阶段使用 `docker/maven-settings.xml` 指向已配置的 Maven 镜像源。

---

## 6. 测试账号与接口鉴权

本项目为本地演示，**未启用鉴权**，所有接口匿名可访问，无需账号。若需要演示审批人字段，请传 `approvedBy` / `decidedBy`（如 `catalog-admin`、`reviewer`），仅作为审计记录。

---

## 7. 演示样例（启动即写入，幂等）

| 对象 | 内容 |
| --- | --- |
| 特征 | `NDVI_DROUGHT`（NDVI 亏缺，含离散度惩罚）、`RAIN_ANOMALY`（60 天降雨亏缺）、`CROP_EXPOSURE`（申报面积 × 生育期系数） |
| 地块 | `P-1001`（两条边界版本 v1/v2，重叠）、`P-1002`（对照） |
| 气象站 | `WS-001`（主）、`WS-002`（次） |
| 案件 | `DROUGHT-2024-0001`（P-1001，锁定 v1）、`DROUGHT-2024-0002`（P-1002） |
| 灾时点 | 2024-06-20；评分截止 2024-06-22T00:00Z |
| 陷阱数据 | 晚到影像 `SCENE-2024-06-18-B`；晚到气象修订 `WS-001-W2024-06-13` rev 2（2024-07-08 入库） |
| 回填实验室特征 | `GDD_AFTER_SOWING`（v1 原始 / v2 校准 ×0.85）、`NDVI_RECOVERY_SLOPE`（v1） |
| 历史案件 | `HIST-2024-0003`（干净）、`HIST-2025-0001`（申报 2025-06-20 晚于截止入库 → 穿越风险）、`HIST-2025-0002`（前段云遮挡）、`HIST-2025-0003`（仅 1 景无云）、`HIST-2026-0001`（温度缺测） |
| 缺失策略 | 云遮挡 v1 插值 / v2 缺失排除；迟到申报 v1 穿越标记；缺测气象 v1 缺失排除 |

`GET /api/demo/scenarios` 会返回场景索引与当前计数。

---

## 8. 复现流程（验收脚本）

服务启动并可访问后执行：

```bash
./scripts/verify.sh                 # 基础三条强制约束，默认 BASE=http://localhost:8080
./scripts/verify-backfill.sh        # 回填实验室：R2 穿越风险 / R3 策略变更 / R4 版本隔离 + 三个查询接口
```

`verify.sh` 依次验证：

1. **晚到影像**：`DROUGHT-2024-0001` 原始运行含 `EXCLUDED_LATE_AVAILABILITY`，且没有任何 `ingestedAt > cutoff` 的可用输入；
2. **重叠边界**：对 v2 发起边界变体运行，出现 ≥1 条 `EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL`，原快照哈希不变；
3. **气象修订**：先将案件结案为 `PAID`，影响分析给出 `RECOMPUTE_CANDIDATE` 并生成 `PENDING_REVIEW` 候选，案件仍为 `PAID`、仍只有 1 个冻结快照且哈希不变；
4. **复现**：`POST /api/cases/.../reproduce` 按原截止时间重放，`valuesMatch=true`。

`verify-backfill.sh` 依次验证（可重复执行，实验不可变所以第二次走复用路径）：

1. **依赖查询**：GDD 依赖 `SOWING_DATE + TEMPERATURE_SERIES`，斜率依赖 `IMAGERY_SEQUENCE`；
2. **R2 穿越风险**：GDD 扫描 7 个案件得 5 个 `EVALUABLE`、2 个 `POST_HOC_ONLY`；`HIST-2025-0001` 为 `LOOKAHEAD_RISK`（原因注明申报入库晚于截止），`HIST-2026-0001` 为 `MISSING`（温度缺测）；
3. **调度与回填状态**：`EXP-SLOPE-INTERP` 执行后 5 片 `COMPUTED`、1 片 `MISSING`、1 片 `SKIPPED_SCOPE`；
4. **R3 策略变更**：同键重建返回 `409 EXPERIMENT_EXISTS`；新建 `EXP-SLOPE-EXCLUDE` 后 `HIST-2025-0003` 变 `MISSING_EXCLUDED`、`HIST-2025-0002` 斜率由 0.001724 变 0.006，而 INTERP 实验的指标与快照数保持不变；
5. **实验对比**：`attribution = SAMPLE_SCOPE_AND_FORMULA_OR_POLICY`，仅单侧可计算案件与共同案件取值差分列；
6. **R4 版本隔离**：`EXP-GDD-V1V2` 共 14 片（7 案件 × 2 版本），`HIST-2024-0003` 存 v1/v2 两行快照且 v2 = v1 × 0.85；两个 `POST_HOC_ONLY` 案件没有任何实验快照。

手工操作示例：

```bash
# 查看案件与运行
curl -s localhost:8080/api/cases/DROUGHT-2024-0001 | jq .
curl -s localhost:8080/api/cases/DROUGHT-2024-0001/trace | jq .

# 看某次运行的输入台账与穿越报告
curl -s localhost:8080/api/runs/1 | jq '{leakage, inputs}'

# 触发气象修订的影响分析（先结案，模拟已付款）
curl -s -XPOST localhost:8080/api/cases/DROUGHT-2024-0001/settle \
  -H 'Content-Type: application/json' -d '{"settledBy":"claims-officer"}' | jq .
EV=$(curl -s "localhost:8080/api/warehouse/revision-events?sourceType=WEATHER_OBSERVATION" | jq -r '[.[]|select(.revision_no==2)][0].id')
curl -s -XPOST localhost:8080/api/impact/analyses/by-revision/$EV | jq .
curl -s localhost:8080/api/review/candidates | jq .

# 复核决定（只记录人工结论，不改快照/赔付）
curl -s -XPOST localhost:8080/api/review/candidates/1/decisions \
  -H 'Content-Type: application/json' \
  -d '{"action":"ACKNOWLEDGE","decidedBy":"reviewer","note":"accepted for manual adjustment"}'
```

---

## 9. 数据模型要点

- `feature_definition` / `feature_definition_version`：定义与审批版本；`checksum` 记录公式摘要；已审批版本由触发器锁定不可改。
- `claim_case_feature_lock`：案件受理时锁定具体定义版本，之后目录再审批新版本也不影响历史案件。
- `feature_run`：`mode ∈ {ORIGINAL, REPLAY, CONTROLLED_BACKFILL}`，携带 `cutoff_at`、`availability_ceiling`、`boundary_version_id`、`boundary_variant`、`parent_run_id`。
- `feature_run_input`：输入台账（`observed_at`、`ingested_at`、`eligibility`、`reason`），是穿越检测与追溯的依据。
- `leakage_report` / `leakage_violation`：持久化检测结论与告警。
- `scoring_snapshot` / `snapshot_feature` / `snapshot_input`：冻结快照，`inputs_hash`（SHA-256）用于复现比对；触发器禁止修改。
- `data_revision_event`：所有修订事件，影响分析入口。
- `impact_analysis` / `impact_case` / `review_candidate`：影响范围、受控回填运行、待复核候选。
- `pixel_claim`：像元唯一归属，用于边界版本隔离。
- `feature_dependency`：特征依赖图（播种日期 / 温度序列 / 影像序列 + 窗口与阈值参数）。
- `missing_policy`：缺失处理策略库，按（策略码， 版本）演进。
- `availability_scan` / `availability_scan_item`：历史可用性扫描结果，含逐依赖判定与案件分层（`EVALUABLE / POST_HOC_ONLY`）。
- `experiment`：实验（特征 + 冻结策略集 + 案件范围 + 扫描依据）。
- `backfill_job` / `backfill_shard`：回填作业与（定义版本 × 案件）分片状态机。
- `experiment_snapshot`：不可变实验快照（输入、输出、缺失原因、策略应用），按（实验， 案件， 定义版本）唯一，触发器禁止 UPDATE/DELETE。

公式语言是一个受限表达式解析器（`FormulaExpression`）：支持 `+ - * /`、括号、数字、聚合变量，以及 `min/max/abs/round/floor/ceil/clamp/coalesce`。**不支持任意函数调用**，避免公式越权访问数据。变量只由通过时点筛选的输入填充。

---

## 10. 已验证内容（实际执行结论）

以下结论均为本机实际执行所得：

- `mvn test`：公式解析/求值单测通过（优先级、聚合变量、拒绝未知函数、除零返回 NaN）。
- `mvn package` 构建成功，`target/agri-pit.jar` 可启动。
- 启动后 Flyway 建表、样例写入成功；两个案件均由队列 worker 执行为 `SUCCEEDED` 并生成快照。
- `./scripts/verify.sh` 全绿：三条强制约束 + 复现流程全部通过。
- `GET /v3/api-docs` 返回 OpenAPI 3.1（48 个路径，含回填实验室 11 个），`/swagger-ui/index.html` 返回 200。
- 数据库层保护实测：直接 `UPDATE scoring_snapshot` 抛 "scoring snapshots are immutable"；直接改已审批公式抛 "approved feature definition versions are immutable"。
- 目录审批实测：对已审批版本重复审批返回 `VERSION_NOT_DRAFT`；新增并审批 v2 后，历史案件 `lockedFeatures` 仍指向 v1，复现 `valuesMatch=true`。
- 状态保护实测：案件结案为 `PAID` 后执行影响分析和复现，状态保持 `PAID`，快照数与哈希不变。
- 数据不足路径实测：构造一个无可用影像的案件，运行为 `LEAKAGE_BLOCKED`（含 `NO_ELIGIBLE_SCENE`），快照数为 0，案件转 `UNDER_REVIEW`。
- 回填实验室实测（全新库 + 重复执行两轮）：`./scripts/verify-backfill.sh` 45 项断言全绿——
  - 扫描器：GDD 7 案件 → 5 `EVALUABLE` / 2 `POST_HOC_ONLY`；`HIST-2025-0001` 判 `LOOKAHEAD_RISK`（申报 2025-06-20 入库 > 截止 2025-06-17）；`HIST-2026-0001` 判 `MISSING`（温度缺测）。
  - 调度器：`EXP-SLOPE-INTERP` 5 片计算、1 片缺失、1 片按范围跳过；`EXP-GDD-V1V2` 14 片 = 7 案件 × 2 版本。
  - R3：同键重建返回 409；`EXP-SLOPE-EXCLUDE` 新建后 `EXP-SLOPE-INTERP` 指标（5 片、均值 0.002997）与快照数（6）不变；数据库层 `UPDATE/DELETE experiment_snapshot` 抛 "experiment snapshots are immutable"。
  - R4：`HIST-2024-0003` 存 v1=1175.998 / v2=999.598 两行快照，比值恰为 0.85，各自公式独立。
  - 对比接口：`EXP-SLOPE-INTERP` vs `EXP-SLOPE-EXCLUDE` 归因 `SAMPLE_SCOPE_AND_FORMULA_OR_POLICY`（`HIST-2025-0003` 仅插值侧可算、`HIST-2025-0002` 共同案件取值 0.001724 vs 0.006）。
- `mvn test`：新增 `SeriesVariablesTest` 6 个用例（积温累加、缺测跳过、窗口裁剪、斜率最小二乘、单景/同日未定义、逐场景聚合）全部通过。

未验证项：当前容器无 Docker 守护进程，`docker compose up` 未实际执行；已提供可构建的 `Dockerfile` / `docker-compose.yml`。

---

## 11. 目录结构

```
src/main/java/com/agri/pit/
  catalog/    特征目录、公式表达式、审批
  ingest/     时点数据仓写入与修订事件
  cases/      案件受理、版本锁定
  runs/       运行队列、执行器、特征求值、worker、时序变量（积温/斜率）
  leakage/    时点取数、像元归属、穿越检测
  snapshots/  快照冻结、回填候选、复现比对
  impact/     修订影响分析、复核候选
  backfill/   回填实验室：依赖图、策略库、可用性扫描、调度器、实验快照仓与对比
  demo/       确定性样例与场景索引
src/main/resources/db/migration/V1__schema.sql
src/main/resources/db/migration/V2__backfill_lab.sql
scripts/verify.sh
scripts/verify-backfill.sh
Dockerfile / docker-compose.yml
```
