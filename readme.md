在D:\KataGo\zhongkequanan\a2\spring-ai-milvus-rag-demo\backend\src\main\resources\application.yml里面配置



在D:\KataGo\zhongkequanan\a2\spring-ai-milvus-rag-demo\backend\src\main\java\com\example\rag\config里面配置



项目使用的是DeepSeek V4

用户端

![8d68a0345ff87bc70daa4e607e5b1729](.\images\8d68a0345ff87bc70daa4e607e5b1729.png)

管理端

![b7ed8d4883026811d49b04de0d320e7a](.\images\b7ed8d4883026811d49b04de0d320e7a.png)

测试

启动D:\KataGo\zhongkequanan\a2\spring-ai-milvus-rag-demo\eval\rag_eval.py



上传文档：python rag_eval.py --upload --base-url http://localhost:8080

检索测评：python rag_eval.py --run-search

对话测评：python rag_eval.py --run-chat



完整流程：上传文档 →  检索评测 → 对话评测

python rag_eval.py --upload --run-search --run-chat



测试结果

![985d9cb861e0bdd3ffd6ac6a717d60c3](.\images\985d9cb861e0bdd3ffd6ac6a717d60c3.png)