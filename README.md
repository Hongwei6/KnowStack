# 知栈 KnowStack

基于 Java 的知识库系统（RAG 引擎模块：know-engine）。

## 技术栈

| 分类 | 技术 | 说明 |
|---|---|---|
| 语言/框架 | Java 21 + Spring Boot 3.5 | 虚拟线程、Record 模式匹配 |
| AI 框架 | LangChain4j 1.11.0 | RAG 管道、AI Services、流式对话 |
| LLM | DeepSeek `deepseek-chat` | 意图识别、查询改写、路由决策、流式生成 |
| 向量存储 | Elasticsearch | KNN / 全文 / 混合检索 |
| 图数据库 | Neo4j + APOC | Text2Cypher 实体关系查询 |
| 关系数据库 | MySQL + MyBatis-Plus | 业务数据 + Text2SQL 结构化查询 |
| 对象存储 | MinIO | 文档文件存储 |
| 缓存 | Redis + Redisson | 同一文档的异步索引构建与手动切分互斥 |
| Reranking | BGE-RERANKER (ONNX Runtime) | 进程内本地推理，零网络开销 |
| Embedding | Yoga Ollama `qwen3-embedding:4b` | 1024 维向量；旧 1536 维索引需重建 |
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

## 依赖服务（家庭服务器，经 ZeroTier 访问）

| 服务 | 位置 | 地址 | 凭据来源 |
|---|---|---|---|
| MySQL 8.4.7 | T480s（10.244.0.111）`1Panel-mysql-qEJL` | 3306 / 库 `knowstack` | `MYSQL_PASSWORD` |
| Redis（Dify 共用，db2） | T480s `docker-redis-1` | 6379 | `REDIS_PASSWORD` |
| Elasticsearch 8.19.10（IK） | T480s `elasticsearch` | 9200 | 无认证 |
| Neo4j 5.22-community | T480s `neo4j` | bolt 7687 | `NEO4J_PASSWORD` |
| XXL-Job Admin 2.4.2 | T480s `xxl-job-admin` | 28080 | accessToken: default_token |
| MinIO | debian（10.244.0.102）`1Panel-minio-tKMN` | 9000（API）/ 9001（控制台） | `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` |
| MinerU 4.1.5 | Yoga（10.244.0.110） | HTTP 8000 `/v1/tasks` | `document/service/MineruClient` 接入 PDF → ZIP 转换 |
| Ollama Embedding | Yoga（10.244.0.110） | HTTP 11434 `/v1/embeddings` | 无认证，ZeroTier 内网 |
| DeepSeek API | 公有云 | `https://api.deepseek.com/v1` | `DEEPSEEK_API_KEY` |

本机开发：复制凭据到 `src/main/resources/application-local.yml`（已 gitignore），再 `mvn spring-boot:run`。

数据库初始文件：`src/main/resources/sql/tables.sql`、`retrieve_tables.sql`（MySQL `knowstack` 库）及 `src/main/resources/neo4j/test-data.cypher`。需要时手动执行；应用启动不自动导入。**不要在已有图数据库整份执行 `test-data.cypher`：其中包含 `MATCH (n) DETACH DELETE n` 和删除约束的语句。**

持久化模型按 `tables.sql` 的 12 张表建立：`document/entity|mapper`（文档、版本、片段、表元数据）、`chat/entity|mapper`（会话、消息）、`business/entity|mapper`（车辆、订单、员工、用户）及 `rag/entity|mapper`（问答缓存）。Mapper 继承 MyBatis-Plus `BaseMapper`，由应用入口统一扫描；`lock_version` 启用乐观锁，带 `deleted` 字段的表启用逻辑删除。SQL `JSON` 列在实体中保留原始 JSON 字符串，不自动解析为对象。

模型由 `ai/config/AiModelConfig` 注册为 LangChain4j 的 `EmbeddingModel`、`ChatModel` 和 `StreamingChatModel`。本机 API Key 放在已忽略的 `application-local.yml`，部署时使用 `DEEPSEEK_API_KEY`；切勿提交密钥。XXL-Job 原 2.3.0 容器保留为 `xxl-job-admin-pre242`，升级前数据库备份位于 T480s 的 `~/xxl_job-before-2.4.2.sql`。

PDF 上传：`POST /api/documents/upload` 将原文件存 MinIO、文档/版本记录写 MySQL，事务提交后发布 `Uploaded` 事件。`document/event/DocumentEventListener` 的异步处理器接力：`Uploaded` → MinerU 解析 PDF、图片描述与 Markdown 存储 → `Converted` → `DocumentSplitService` 保存有序父子分段 → `Chunked` → `SegmentEmbeddingService` 写 Elasticsearch，状态到 `VECTOR_STORED`。事件只携带文档 ID，原始 PDF 从 MinIO 读取；每阶段按持久化状态幂等判定，同一文档使用 Redisson 锁 `document:processing:{id}`。`document/job/DocumentStageRecoveryJob` 每 60 秒扫描超时状态并补发事件（上传/转换/分段超过 1 分钟，转换中超过 15 分钟）；进程内事件不依赖消息队列，数据库状态负责重启补偿。手动切分遇占用返回 HTTP 409，切分事务提交后再发 `Chunked` 事件。
