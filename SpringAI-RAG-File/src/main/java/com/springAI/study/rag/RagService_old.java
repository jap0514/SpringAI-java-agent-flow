package com.springAI.study.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.springAI.study.Agent.ToolExecutor_planner;
import com.springAI.study.functions.DepartmentFunction;
import com.springAI.study.model.ChatMemory;
import com.springAI.study.model.Plan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService_old {
    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ToolExecutor_planner toolExecutor;

    @Autowired
    private DepartmentFunction departmentFunction;

    public RagService_old(VectorStore vectorStore, ChatModel chatModel){
        this.chatModel=chatModel;
        this.vectorStore=vectorStore;
    }

    //查询理解
    private String rewriteQuery(String originalQuestion) {
        String rewritePrompt = """
                你是企业知识库的查询优化专家。
                用户输入了一个问题，请将其改写成更清晰、专业、包含关键实体和上下文的版本，
                使其更适合向量数据库检索（保持原意，不要添加新信息）。
                只输出改写后的查询，不要有任何解释、标点或多余文字。
                
                用户原始问题：
                %s
                """.formatted(originalQuestion);
        System.out.println("查询理解开始");
        //不可以return chatModel.call(originalQuestion) 会连接超时
        return chatModel
                .call(new Prompt(rewritePrompt))
                .getResult()
                .getOutput()
                .getText()
                .trim();
//        return originalQuestion;
    }

    //RAG查询流程
    public String query(String question, ChatMemory chatMemory) throws JsonProcessingException {

        Plan plan=planner(question);



        String finalQuery=question;
        //判断是否需要查询理解
        if (plan.isNeedRewrite()) {
            finalQuery = rewriteQuery(question);
        }

        //验证是否需要调用工具
        String toolResult = "";
        if (plan.isNeedTool()) {
            toolResult = toolExecutor.execute(plan);
        }

        List<Document> results = List.of();

        //判断是否需要检索
        if (plan.isNeedRetrieval()) {
            results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(plan.getRetrievalQuery())
                            .topK(2)
                            .build()
            );
        }


        // 1. 先进行查询理解，得到优化后的查询
        //String rewrittenQuestion = rewriteQuery(question);

        //2、提取关键词
        List<String> keywords=extractKeywords(finalQuery);

        System.out.println("【原始问题】: " + question);
        System.out.println("【优化后查询】: " + finalQuery);  // 调试用，上线可删
        System.out.println("【提取关键词】:" + keywords);

        //1、从Qdrant中检索 top 文档
//        List<Document> results=vectorStore.similaritySearch(SearchRequest.builder()
//                .query(rewrittenQuestion)
//                .topK(2)
//                .similarityThreshold(0.5)   //过滤低相似度
//                .build());

        //关键词匹配加权
        results.forEach(
                document -> {
                    long matchCount=keywords.stream().filter(kw-> document.getText().contains(kw)).count();
                    document.getMetadata().put("keyword_score",(double)matchCount);
                }
        );

        results.forEach(doc -> {
            double vector = (double) doc.getMetadata().getOrDefault("vector_score", 0.0);
            double keyword = (double) doc.getMetadata().getOrDefault("keyword_score", 0.0);
            double combined = vector * 0.7 + keyword * 0.3;
//            System.out.printf("文档: %s\n向量: %.3f, 关键词: %.3f, 综合: %.3f\n",
//                    doc.getText(), vector, keyword, combined);
        });

        //Spring AI 的 similaritySearch 返回了不可变 List
        //所以需要进行转换
        List<Document> sortedResults = new ArrayList<>(results);

        //融合排序
        sortedResults.sort((d1,d2)->{
            double v1 = (double) d1.getMetadata().getOrDefault("vector_score", 0.0);
            double k1 = (double) d1.getMetadata().getOrDefault("keyword_score", 0.0);
            double v2 = (double) d2.getMetadata().getOrDefault("vector_score", 0.0);
            double k2 = (double) d2.getMetadata().getOrDefault("keyword_score", 0.0);
            return Double.compare((v2*0.7 + k2*0.3), (v1*0.7 + k1*0.3));
        });

        //2、拼接 context
        String context=results.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));

        //3、构建Prompt
        String prompt= """
                你是企业知识库助手。
                只能根据以下内容回答问题，如果内容里没有答案，请回答“不知道”。
                [历史对话]
                %s
                [参考资料]
                %s
                [问题]
                %s
                """.formatted(chatMemory.GetHistoryContext(),context,question);

        //这里为了注册自定义工具，使用ChatClient
        String answer = chatClientBuilder.build()   // 或直接用注入的 chatClient
                .prompt()
                .system("""
                你是企业知识库助手。
                只能根据以下内容回答问题，如果内容里没有答案，请回答“不知道”。
                当需要知道某个部门的员工数量时，可以使用 getEmployeeCount 工具查询。
                """)
                .user("""
                [历史对话]
                %s
                
                [参考资料]
                %s
                
                [使用工具结果]
                %s
                
                [问题]
                %s
                """.formatted(chatMemory.GetHistoryContext(), context,toolResult, question))
                .tools(departmentFunction)               // ← 关键：直接传 @Component 实例
                // 也可以用 .tools("getEmployeeCount") 如果你想只传名字（需bean名称一致）
                .call()
                .content();


        //4、调用 ChatModel 生成答案
        //String answer=chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();

        chatMemory.addTurns(question,answer);

        System.out.println("历史对话: "+chatMemory.GetHistoryContext());

        return answer;
    }

    private List<String> extractKeywords(String rewrittenQuestion) throws JsonProcessingException {
        String prompt= """
                任务：请从用户的提问中提取关键词，去掉无意义词，并以JSON数组的形式输出。
                用户提问: "%s"
                输出示例: ["财务部门","2025年","报表"]
                """.formatted(rewrittenQuestion);

        String response = chatModel.call(new Prompt(prompt)).getResult().getOutput().getText();

        ObjectMapper mapper=new ObjectMapper();
        return mapper.readValue(response, new TypeReference<List<String>>() {
        });

    }

    //计划
    public Plan planner(String question) throws JsonProcessingException {

        String response = chatModel.call(
                new Prompt(plannerPrompt(question))
        ).getResult().getOutput().getText();

        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(response, Plan.class);
    }

    //计划生成器
    private String plannerPrompt(String question) {
        return """
    你是一个 RAG 系统的 Planner（执行计划生成器）。

    根据用户问题，判断系统需要做哪些步骤。
    你【不需要回答问题】。

    可用能力：
    - 向量检索（Retrieval）
    - 历史对话（Memory）
    - 工具调用（Tool）
    
    可用工具：
    1、add_int(a:int,b:int)----两整数相加
    2、add_double(a:double,b:double)----两小数相加

    请只输出 JSON，格式如下：
    {
      "needRewrite": true|false,
      "needRetrieval": true|false,
      "needMemory": true|false,
      "needTool": true|false,
      "toolName":{...},
      "toolArgs":{...},
      "retrievalQuery": "..."
    }

    规则：
    - 如果是数学计算 -> needTool=true
    - toolName 必须是字符串，如 add_int、add_double，不可以是对象
    - toolArgs 必须完整
    - 如果问题是延续上文（如“那它呢”“继续说”），need_memory=true
    - 如果是事实/知识问题，need_retrieval=true
    - retrieval_query 必须是一个完整、明确、适合检索的句子
    - 不要输出任何解释

    用户问题：
    %s
    """.formatted(question);
    }

    //反思，检查回答是否正确

}
