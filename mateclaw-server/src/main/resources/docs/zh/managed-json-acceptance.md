# Goal 受管 JSON 验收

在 Goal 面板展开“JSON 验收要求”，由对话所有者或管理员显式保存要求。每个 Goal 最多 8 条要求，每条绑定一个产物槽和 1–16 个顶层字段。字段检查表示字段存在且不为 null；false、0 和空字符串允许，不等于内容质量判断。保存后不可关闭强验收模式，可以带当前 revision 修改要求；旧修订会返回冲突。

当前阶段已提供用户配置与独立受管版本存储；绑定检查和强验收成功完成路径尚未接通。选中此模式的 Goal 暂时拒绝完成，不会回退到文字声明；未选中的 Goal 保持既有行为。

## 受管版本接口

接口前缀 `/api/v1/goals/{goalId}/json-acceptance`，需要启用账户及对话所有者或管理员权限。ID、revision 和 generation 在响应中使用字符串，客户端应原样保留。

- `GET /`：读取启用状态和要求。
- `PUT /requirements/{criterionKey}`：提交 `expectedRevision`、`artifactSlot`、`requiredFields`。新要求的 revision 为 `0`。
- `GET /artifacts`：列出当前要求使用的槽及当前版本。空槽 generation 为 `0`。
- `POST /artifacts/{slot}`：提交 `expectedGeneration` 和 `jsonContent`（包含原始 JSON 正文的字符串），原子追加新版本并推进槽。
- `GET /artifacts/versions/{artifactId}`：读取本 Goal 指定版本的元数据及原始正文，包括历史版本；读取历史版本不表示它仍可用于验收。

仅当前要求引用的槽可发布，Goal 必须 active 或 paused。正文必须是严格 JSON 对象，拒绝重复键、尾随文档、超过 32 层的嵌套及超过 1 MiB 的 UTF-8 内容。每个 Goal 最多保存 32 个版本；达到配额拒绝继续发布，不覆盖旧版本。每版有效期 24 小时，重复发布同样正文也产生新版本。客户端遇到 generation 冲突应重新读取，不自动覆盖他人发布。

这些版本独立存储在数据库，不能用普通工作区文件、缓存路径或文字中的 hash 替代。发布接口不支持更新历史正文；所有版本与槽指针同事务保存。SHA-256 用于标识及完整性核对，不能隔离拥有数据库凭据或宿主权限的攻击者；数据库和服务宿主是此有限协议的可信基础。当前未对 MySQL、Kingbase/PostgreSQL 实例执行迁移验收；H2 服务集成测试不等于外部数据库验证。
