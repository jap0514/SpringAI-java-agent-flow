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

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagService {

    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ToolExecutor_planner toolExecutor;

    @Autowired
    private DepartmentFunction departmentFunction;

    private final ObjectMapper mapper = new ObjectMapper();

    public RagService(VectorStore vectorStore, ChatModel chatModel){
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    /** 查询理解 */
    private String rewriteQuery(String originalQuestion) {
        String rewritePrompt = """
                你是企业知识库的查询优化专家。
                用户输入了一个问题，请将其改写成更清晰、专业、包含关键实体和上下文的版本，
                使其更适合向量数据库检索（保持原意，不要添加新信息）。
                只输出改写后的查询，不要有任何解释、标点或多余文字。
                
                用户原始问题：
                %s
                """.formatted(originalQuestion);

        return chatModel.call(new Prompt(rewritePrompt))
                        .getResult()
                        .getOutput()
                        .getText()
                        .trim();
    }

    /** 提取关键词 */
    private List<String> extractKeywords(String rewrittenQuestion) throws JsonProcessingException {
        String prompt= """
                任务：请从用户的提问中提取关键词，去掉无意义词，并以JSON数组的形式输出。
                用户提问: "%s"
                输出示例: ["财务部门","2025年","报表"]
                """.formatted(rewrittenQuestion);

        String response = chatModel.call(new Prompt(prompt))
                                   .getResult()
                                   .getOutput()
                                   .getText();

        return mapper.readValue(response, new TypeReference<List<String>>() {});
    }

    /** Planner：生成执行计划 */
    public Plan planner(String question) throws JsonProcessingException {
        String response = chatModel.call(new Prompt(plannerPrompt(question)))
                                   .getResult()
                                   .getOutput()
                                   .getText();
        return mapper.readValue(response, Plan.class);
    }

    /** Planner prompt */
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
        1、add_int(a:int,b:int)
        2、add_double(a:double,b:double)
        3、getEmployeeCount(department:String)

        请只输出 JSON，格式如下：
        {
          "needRewrite": true|false,
          "needRetrieval": true|false,
          "needMemory": true|false,
          "needTool": true|false,
          "toolName": "...",
          "toolArgs": {...},
          "retrievalQuery": "..."
        }

        规则：
        - 数学计算 -> needTool=true
        - toolName 必须是字符串
        - toolArgs 必须完整
        - 延续上文 -> needMemory=true
        - 事实/知识问题 -> needRetrieval=true
        - retrieval_query 必须完整明确
        - 不输出任何解释

        用户问题：
        %s
        """.formatted(question);
    }

    /** Reflection：检查回答是否正确，如果不正确生成新计划 */
    public Plan reflection(String question, String answer) throws JsonProcessingException {
        String reflectionPrompt = """
        你是 RAG 系统的反思模块（Reflection）。

        用户问题：
        %s

        模型初步回答：
        %s

        判断：
        1. 回答是否正确完整？
        2. 是否需要额外的工具调用或知识检索？

        输出 JSON：
        - 如果正确完整：
        {"replan": false}
        - 如果不完整或错误：
        {"replan": true,
         "needRewrite": true|false,
         "needRetrieval": true|false,
         "needMemory": true|false,
         "needTool": true|false,
         "toolName": "...",
         "toolArgs": {...},
         "retrievalQuery": "..."
        }
       
        规则：
        - toolName 必须是字符串，如 add_int、add_double，不可以是对象
        - toolArgs 必须完整

        只输出 JSON。
        """.formatted(question, answer);

        String response = chatModel.call(new Prompt(reflectionPrompt))
                                   .getResult()
                                   .getOutput()
                                   .getText()
                                   .trim();

        // 如果 JSON 中只有 replan=false，则直接返回 null
        if (response.contains("\"replan\": false")) {
            return null;
        }

        //将大模型返回的结果转换为Plan对象，再返回
        return mapper.readValue(response, Plan.class);
    }

    /** 执行计划的统一方法 */
    public String executePlan(Plan plan, String question, ChatMemory memory) throws JsonProcessingException {
        //检查是否需要查询理解
        String finalQuery = plan.isNeedRewrite() ? rewriteQuery(question) : question;
        //检查是否需要使用工具
        String toolResult = plan.isNeedTool() ? toolExecutor.execute(plan) : "";

        //检查是否需要向量检索
        List<Document> docs = plan.isNeedRetrieval() ?
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(plan.getRetrievalQuery())
                                .topK(2)
                                .build()
                ) : List.of();

        //这行代码的作用是将检索到的多个文档片段提取出文本内容，并用分隔符拼接成一个完整的长字符串，作为后续大模型回答的背景知识
        /**
         * docs.stream()--->将多个文档列表转换成一个流，开启函数式编程，以便对列表中的每一个元素进行批量处理，而不是写传统的for循环
         * map.(Document::getText)-->这一步就是调用流中的Document对象中的getText方法来获取纯文本内容，丢弃另外的一些文件名、向量分数等的元数据
         * collect(Collectors.joining("\n---\n")-->这个就是将流中的所有字符串拼接成一个单独的大字符串
         */
        String context = docs.stream().map(Document::getText).collect(Collectors.joining("\n---\n"));

        // 构建 ChatClient 调用，支持工具
        String answer = chatClientBuilder.build()
                .prompt()
                .system("你是企业知识库助手。只能根据以下内容回答问题，如果内容里没有答案，请回答“不知道”。")
                .user("""
                        [历史对话]
                        %s
                        [参考资料]
                        %s
                        [工具结果]
                        %s
                        [问题]
                        %s
                        """.formatted(memory.GetHistoryContext(), context, toolResult, finalQuery))
                .tools(departmentFunction)
                .call()
                .content();

        return answer;
    }

    /** 主流程：Planner + 执行 + Reflection + Replan */
    public String queryWithPlannerAndReflection(String question, ChatMemory memory) throws JsonProcessingException {
        Plan plan = planner(question);
        String answer = executePlan(plan, question, memory);

        // Reflection
        Plan replan = reflection(question, answer);
        if (replan != null) {
            System.out.println("Reflection发现答案不完整，重新执行计划...");
            answer = executePlan(replan, question, memory);
        }

        memory.addTurns(question, answer);
        System.out.println("历史对话: " + memory.GetHistoryContext());

        return answer;
    }
}