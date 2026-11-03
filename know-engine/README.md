# 知栈 KnowStack

基于 Java 的知识库系统（RAG 引擎模块：know-engine）。

## 技术栈

| 分类 | 技术 | 说明 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.5 | 虚拟线程、Record 模式匹配 |
| AI 框架 | LangChain4j 1.11.0 | RAG 管道、AI Services、流式对话 |
| LLM | qwen-max-latest、qwen3.5-flash、qwen-3.6-plus | 意图识别、查询改写、路由决策、流式生成 |
| 向量存储 | Elasticsearch | KNN / 全文 / 混合检索 |
| 图数据库 | Neo4j + APOC | Text2Cypher 实体关系查询 |
| 关系数据库 | MySQL + MyBatis-Plus | 业务数据 + Text2SQL 结构化查询 |
| 对象存储 | MinIO | 文档文件存储 |
| 缓存 | Redis + Redisson | 分布式锁、父分段缓存 |
| Reranking | BGE-RERANKER (ONNX Runtime) | 进程内本地推理，零网络开销 |
| Embedding | text-embedding-v4 (通义) | 1536 维向量 |
| 文件解析 | MinerU (PDF)、Apache Tika、EasyExcel | 多格式文档解析 |
| 任务调度 | XXL-Job 2.4 | 补偿任务定时触发 |
| 响应式 | Project Reactor | SSE 流式推送、非阻塞调度 |
| 连接池 | Druid | SQL 监控、慢查询检测 |

## 代码结构

```
know-engine/
├── ai/           # AI 能力层：Memory 配置、意图枚举、意图识别、通用对话、Prompt 路由、标题摘要
├── chat/         # 对话管理层：会话/消息实体与接口、RAG 管道编排
├── config/       # 全局配置：线程池、自动填充、MyBatis-Plus 插件、XXL-Job 执行器
├── document/     # 知识文档管理层：MinIO、文档状态、切片、事件、补偿任务
├── infra/        # 基础设施：JSON 工具、分布式锁注解 + AOP、雪花 ID
└── rag/          # RAG 检索增强层：ES/Neo4j 配置、查询改写、三源路由、检索、Rerank、切片器
```
