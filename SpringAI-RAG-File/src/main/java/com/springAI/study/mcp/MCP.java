package com.springAI.study.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.springAI.study.model.ChatMemory;
import com.springAI.study.model.Plan;
import com.springAI.study.rag.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Model Control Plane (MCP)
 * 进阶版：
 * - 自动生成执行计划
 * - 执行计划（RAG检索 + 工具调用 + Memory）
 * - Reflection 自动判断是否需要 Replan
 * - 最大重规划次数可配置
 * - 自动选择工具和检索策略
 */
@Component
public class MCP {

    @Autowired
    private RagService ragService;

    private final int MAX_REPLAN = 2; // 最大 Replan 次数

    /**
     * 用户提问入口
     *
     * @param question 用户问题
     * @param memory   历史对话
     * @return 最终答案
     */
    public String handleQuestion(String question, ChatMemory memory) throws JsonProcessingException {
        System.out.println("调用mcp");
        int attempt = 0;
        String answer = null;
        Plan plan = null;

        while (attempt <= MAX_REPLAN) {

            // 1️⃣ 生成执行计划
            plan = ragService.planner(question);

            // 2️⃣ 执行计划（包含 RAG + 工具 + Memory）
            answer = ragService.executePlan(plan, question, memory);

            // 3️⃣ Reflection 判断
            Plan replan = ragService.reflection(question, answer);

            if (replan == null) {
                // 回答正确，结束
                break;
            }

            // 4️⃣ Reflection 提示 Replan，修改 question / plan
            System.out.println("Reflection发现答案不完整，触发 Replan 第 " + (attempt + 1) + " 次");
            question = replan.getRetrievalQuery() != null ? replan.getRetrievalQuery() : question;

            attempt++;
        }

        // 5️⃣ 保存对话历史
        memory.addTurns(question, answer);
        return answer;
    }
}