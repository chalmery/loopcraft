# 模型接入 SQL

先执行 `identity/README.md` 的三个文件，再依次执行：

```bash
./scripts/dev.sh db sql llm/create_llm_source.sql
./scripts/dev.sh db sql llm/create_llm_model.sql
./scripts/dev.sh db sql llm/add_model_capabilities.sql
```

在仓库根目录运行，已有数据库仅执行尚未应用的文件。来源外键限制租户删除；模型组合外键校验来源与租户一致。删除来源由应用事务先删模型再删来源，不用数据库级联掩盖业务引用。模板模块通过限制删除外键保护运行模型和摘要模型，仍沿用先锁来源再操作模型的顺序。

能力增量增加最大上下文及温度支持情况/范围，历史记录为空，需要在模型接入维护；不通过模型名称猜测能力。模板预算留空时采用这里维护的上下文上限。
