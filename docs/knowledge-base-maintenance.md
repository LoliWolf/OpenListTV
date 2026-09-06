# 知识库维护

产品知识入口：[首页](https://my.feishu.cn/wiki/LojHwxGlOiAofDk2JLocRIYinnb)。

负责人在 1.0.0 发布任务中明确要求：把知识库改为首页和使用文档，移除原 PRD；不要再把 PRD 当作对外入口。关键产品决定与架构已整理到根目录 AGENTS.md。

## 范围与映射

- space ID：7682261063663897529。
- 首页 wiki node：LojHwxGlOiAofDk2JLocRIYinnb；底层 docx：J5Nid5ocdoVqjmxf56lcK4jHnpb。
- 使用文档：在首页直接子节点中按标题“使用文档”唯一定位；不猜 token，不重复创建。
- 已发布使用文档：[使用文档](https://my.feishu.cn/docx/VXlLdkASMoCiGdxdhItcr9kYnfh)，wiki node 为 ERHmwJswui0Im6kV4nlcO3v7n0e；2026-09-06 回读确认 6 个章节及 2 张截图。首页更新后回读 revision 3。
- 本地对应指南：docs/USAGE.md；README 只保留功能、截图、下载与首页链接。

## 操作约束

使用可用的飞书文档/知识库/云盘技能，显式 user 身份。每次先读取最新内容，限定目标后编辑，回读验证。不要对整个知识空间做批量移动、改权限或删除。

原 PRD 的远端完整 JSON、初稿、实施稿及旧环境记录备份在忽略的 artifacts/knowledge-backup 中，不提交 GitHub。删除前必须确认新指南与首页已经可用；备份仅能恢复正文资料，不承诺原页面 ID 或完整评论历史可恢复。

本次删除已完成：原 PRD 节点 IXlCwPCjai9KF6k39XEccPqnnie 返回 success；首页子节点只剩使用文档，旧 docx 返回已删除。没有删除首页、知识空间或其他文档，没有修改共享权限。

1.0.0 的上述迁移按本次用户明确指令执行；以后删除或扩大公开权限需重新获得授权。链接是否可匿名访问取决于飞书设置；本次不擅自扩大共享权限，GitHub 的 USAGE.md 是公开的备用指南。

更新版本时同步首页下载链接、使用文档、README、AGENTS.md、测试记录和 Release；不默认创建后台定时维护任务。
