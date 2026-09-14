# Goal 受管 JSON 验收

在 Goal 面板展开“JSON 验收要求”，由对话所有者或管理员显式保存要求。每个 Goal 最多 8 条要求，每条绑定一个产物槽和 1–16 个顶层字段。字段检查表示字段存在且不为 null；false、0 和空字符串允许，不等于内容质量判断。保存后不可关闭强验收模式，可以带当前 revision 修改要求；旧修订会返回冲突。

当前已接通用户配置、独立受管版本、绑定检查及共享完成检查。选中模式后，所有当前要求必须具有匹配的有效绑定，才可在既有完成规则满足时完成；自动评估、显式 completeGoal 和重试均使用同一完成检查。未选中的 Goal 保持既有行为。

## 受管版本接口

接口前缀 `/api/v1/goals/{goalId}/json-acceptance`，需要启用账户及对话所有者或管理员权限。ID、revision 和 generation 在响应中使用字符串，客户端应原样保留。

- `GET /`：读取启用状态和要求。
- `PUT /requirements/{criterionKey}`：提交 `expectedRevision`、`artifactSlot`、`requiredFields`。新要求的 revision 为 `0`。
- `GET /artifacts`：列出当前要求使用的槽及当前版本。空槽 generation 为 `0`。
- `POST /artifacts/{slot}`：提交 `expectedGeneration` 和 `jsonContent`（包含原始 JSON 正文的字符串），原子追加新版本并推进槽。
- `GET /artifacts/versions/{artifactId}`：读取本 Goal 指定版本的元数据及原始正文，包括历史版本；读取历史版本不表示它仍可用于验收。

仅当前要求引用的槽可发布，Goal 必须 active 或 paused。正文必须是严格 JSON 对象，拒绝重复键、尾随文档、超过 32 层的嵌套及超过 1 MiB 的 UTF-8 内容。每个 Goal 最多保存 32 个版本；达到配额拒绝继续发布，不覆盖旧版本。每版有效期 24 小时，重复发布同样正文也产生新版本。客户端遇到 generation 冲突应重新读取，不自动覆盖他人发布。

这些版本独立存储在数据库，不能用普通工作区文件、缓存路径或文字中的 hash 替代。发布接口不支持更新历史正文；所有版本与槽指针同事务保存。SHA-256 用于标识及完整性核对，不能隔离拥有数据库凭据或宿主权限的攻击者；数据库和服务宿主是此有限协议的可信基础。当前未对 MySQL、Kingbase/PostgreSQL 实例执行迁移验收；H2 服务集成测试不等于外部数据库验证。

## 代理发布

`getManagedGoalJsonSlots` 返回当前 Goal 的用户要求、槽和 generation；`publishManagedGoalJson` 接收 `artifactSlot`、字符串 `expectedGeneration` 和 `jsonContent`。工具不能配置要求，也不能传 Goal ID、账户或 owner fence。普通会话必须携带已认证账户的内部 ID；持久 Goal 的调度执行必须同时匹配当前 continuation、attempt、owner token 和有效租约。两种入口都重新检查对话、工作区、Agent 和启用账户。代理委派的默认禁止列表包含这两个工具，服务仍独立检查身份。

发布与调度结算按 Goal 锁串行化，晚到的旧 owner 不得继续写入。租约结束不会改写已经合法发布的历史版本。匿名会话和没有绑定 Goal attempt 的 cron 不支持此发布协议；身份缺失直接拒绝，不以显示用户名代替认证。


## 绑定检查

发布后，调用 `POST /checks/{criterionKey}`，提交 `expectedRequirementRevision`、`artifactId`、`expectedGeneration`；代理使用 `checkManagedGoalJson` 传相同字段。所有修订和 generation 在代理工具里都是字符串。服务端只检查当前槽的指定版本，运行自己的字段 recipe，不接受调用方提供的 PASS。返回 `acceptanceEligible=true` 表示这条要求当前匹配，不能代表整个 Goal 已完成。

`GET /checks` 读取每条要求的当前资格。要求修改、Goal 定义修改、槽出现新版本、版本过期或正文完整性失败都会使旧绑定失效；需要按当前条件重新检查。每次绑定与 Goal version 更新同事务，失败回滚不留下通过凭据。历史诊断接口的 `acceptanceEligible=false` 保持不变，只有此受管版本检查产生绑定。完成事件保留本次受管绑定的条件修订、产物 ID 和 generation 引用；事务回滚不发布完成事件或完成记忆。

这是逐 Goal 显式选择的有限受管 JSON 协议；宽泛执行证据账本的全局 ENFORCE 配置仍遵循原有准入限制。此协议不把任何普通工具成功文本或诊断 MATCH 自动升级为绑定。后端服务已覆盖成功、失效、竞争和回滚；完整浏览器服务闭环、重启和外部数据库验证仍在推进。
