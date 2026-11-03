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

## 依赖服务（家庭服务器，经 ZeroTier 访问）

| 服务 | 位置 | 地址 | 凭据来源 |
|---|---|---|---|
| MySQL 8.4.7 | T480s（10.244.0.111）`1Panel-mysql-qEJL` | 3306 / 库 `knowstack` | `MYSQL_PASSWORD` |
| Redis（Dify 共用） | T480s `docker-redis-1` | 6379 | `REDIS_PASSWORD` |
| Elasticsearch 8.19.10（IK） | T480s `elasticsearch` | 9200 | 无认证 |
| Neo4j 5.22-community | T480s `neo4j` | bolt 7687 | `NEO4J_PASSWORD` |
| XXL-Job Admin 2.3.0 | T480s `xxl-job-admin` | 28080 | accessToken: default_token |
| MinIO | debian（10.244.0.102）`1Panel-minio-tKMN` | 9000（API）/ 9001（控制台） | `MINIO_ACCESS_KEY`/`MINIO_SECRET_KEY` |

本机开发：复制凭据到 `src/main/resources/application-local.yml`（已 gitignore），再 `mvn spring-boot:run`。

> 注意：XXL-Job 执行器客户端（2.4.x）与 Admin（2.3.0）版本不一致，接入前建议升级 Admin 或降级客户端。
