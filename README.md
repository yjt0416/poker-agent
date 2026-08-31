# Agent Tavern

Agent Tavern 是一款以多 Agent 为核心的趣味德州扑克游戏。本仓库目前从模块化 Java 21 后端起步，优先构建可复现、可验证的德州扑克规则引擎。

## 当前进度：第一阶段

当前完成的是确定性的内存扑克引擎垂直切片，包括：

- 扑克牌值对象、不可变牌堆与完整牌型计算；
- 合法下注、完整下注轮与短筹码 all-in 规则；
- 从发牌到结算的完整手牌流程与不可变领域事件；
- 主池、边池、平分底池与奇数筹码分配；
- 确定性性质校验、模块边界和架构依赖守卫。

浏览器前端、HTTP API、数据库以及 Agent/LLM 集成尚未在本阶段实现。引擎的公开语义和确定性测试方式请参阅[后端扑克引擎指南](backend/README.md)。

引擎使用 JUnit Jupiter 执行确定性的性质校验。每项校验使用固定且可复现的场景种子，不依赖 jqwik 或自动收缩。

## 环境要求

安装 Java 21。

## 验证项目

Windows：

```powershell
backend\mvnw.cmd -f backend\pom.xml verify
```

只运行引擎性质校验与架构守卫：

```powershell
backend\mvnw.cmd -f backend\pom.xml -Dtest="HandInvariantProperties,GameEngineDependencyTest" test
```

Unix 或 CI：

```sh
./backend/mvnw -f backend/pom.xml verify
```

## 密钥安全

禁止将真实的 DeepSeek API Key 提交到 Git。凭据只能保存在本地且不受 Git 跟踪的配置中。
