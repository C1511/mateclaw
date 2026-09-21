# 渠道问数历史新鲜度治理

## 问题与根因

飞书、微信等渠道通常按渠道配置和聊天/发送者生成固定 conversationId。固定 ID 用于消息归属、推送和定时任务路由是合理的，但不能因此把该会话的所有历史当成当前数据。

此次源码排查发现：

1. `ChannelMessageRouter.buildConversationId` 没有时间维度，同一聊天长期复用会话。
2. `BaseAgent.buildConversationHistory` 原先只按消息数量分页、按压缩边界回放，未检查 `createTime`。分页还会补回窗口外的最近压缩摘要；旧查询数值可以通过旧答案和摘要重复进入模型。
3. `DshConversationHistory` 原先只限制最近 40 条、4096 token，没有时间过滤，且回放文本不带消息时间。
4. `ChannelSessionStore` 的 30 天 TTL 只在缓存超量时淘汰内存推送地址映射，不删除数据库记录，也不控制模型历史。
5. `ConversationWindowManager.compactAgedToolResponses` 的 age 指工具结果的相对顺序，不是实际经过的分钟数；压缩和 token 裁剪不能替代业务数据有效期。
6. 图片追问会自动携带最近若干条消息里的图片，原先也没有时间限制，可能带回旧报表。

这是根据源码和回归用例确认的上下文污染路径；没有读取生产渠道日志或连接生产数据源。

## QwenPaw 参考结论

参考本机 `/Users/mate/Codes/Open-Source/ai/QwenPaw`：

- `src/qwenpaw/app/channels/feishu/channel.py::resolve_session_id` 和 `wechat/channel.py::resolve_session_id` 同样使用稳定的发送者/群标识。
- `src/qwenpaw/app/chats/session.py::SafeJSONSession.load_session_state` 从 JSON 恢复状态时未在该入口判定业务数据是否过期。
- `ToolResultPruningConfig` 区分近期和较早的工具结果大小；`ScrollContextConfig.history_retention_days` 默认 30 天，管理持久历史保留。这些是上下文容量与存储管理机制，不能视为“查询数据在此时间内仍然有效”的承诺。

借鉴其持久历史与活动上下文分离的思路，在 MateClaw 修复模型入口。本次未修改 QwenPaw 仓库。

## 当前修复

StateGraph 与 DSH 共用 `ChannelHistoryPolicy`，仅改变本轮模型输入，不修改持久记录、会话 ID 或推送映射。默认覆盖有 `channelType` 的非 Web、非定时任务调用，包括飞书、微信、企业微信、钉钉及 webchat。

| 层级 | 默认时间 | 模型输入 |
| --- | --- | --- |
| 热 | 最近 30 分钟 | 保留完整轮次；对话文本标记原始消息时间，仍是历史证据 |
| 温 | 30 分钟至 24 小时 | 保留用户原问题供理解指代；旧助手答案、工具调用/结果和结构化载荷替换为过期提示 |
| 冷 | 超过 24 小时 | 不自动回放；原记录继续保留 |

时间边界按整个用户轮次中最早的消息时间判断，避免在工具调用与结果之间截断。缺失时间、未来时间的轮次不自动回放。所有渠道压缩摘要不再自动注入，因为摘要生成时间不能证明其引用的数据新鲜。此取舍会减少长会话跨天的自动续接能力；用户需重新提供必要条件，或明确请求查阅历史。

两条引擎都附加新鲜度规则：当前/最新/今日问数必须在本轮查询权威数据源，说明查询时间和数据期间；查询失败或不可用时说明无法核验，不用旧值代替。自动携带历史图片只允许热层图片。用户本轮主动提供的图片不受这个历史限制影响。

配置示例（分钟，服务端本地时间口径，与现有 `createTime` 一致）：

```yaml
mate:
  agent:
    channel-history:
      enabled: true
      hot-minutes: 30
      warm-minutes: 1440
```

默认无需配置即可生效；重启运行更新后的服务端。`enabled: false` 可回到原历史回放行为。实现将负数按 0 处理，温层上限至少为热层上限；建议配置满足 `0 <= hot-minutes <= warm-minutes`。

## 验证与落地建议

回归覆盖冷热边界、旧结果及其答案移除、结构化载荷清除、摘要防回填、工具调用轮次完整性、原记录不变、DSH SQL 加载与当前消息去重、Web/定时任务兼容，以及历史图片的时间限制。

验证命令：

```sh
mvn -pl mateclaw-server -am \
  -Dtest='ChannelHistoryPolicyTest,BaseAgent*Test,DshConversationHistoryTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

上线验收建议：同一飞书/微信聊天先查询一次库存，改变测试数据后立即再问“现在库存多少”，再分别构造两小时前和两天前的历史；核查本轮工具日志确实重新查询、回答中的时间和数值正确。另测数据源不可用时是否明确说明无法核验。

30 分钟是对话保留窗口，不是业务数据缓存 TTL。本次硬性移除了过期历史结果，但“最新问数必查”的规则仍由模型执行，不能宣称是工具调用强制校验。对于库存、余额、实时经营数据，后续应按数据源 SLA 增加结构化 `queried_at / data_as_of / valid_until`，在回答出口验证本轮查询凭证；长期记忆只沉淀指标口径、表结构、用户偏好，瞬时数值应带时间和有效期。此次未改造长期记忆的存储与检索，也未实施生产发布。
