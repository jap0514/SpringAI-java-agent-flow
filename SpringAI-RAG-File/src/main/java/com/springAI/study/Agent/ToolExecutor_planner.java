package com.springAI.study.Agent;

import com.springAI.study.functions.NumberFunction;
import com.springAI.study.model.Plan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ToolExecutor_planner {

    @Autowired
    private NumberFunction numberFunction;

    public String execute(Plan plan) {

        if (!plan.isNeedTool()) {
            return "";
        }

        String tool = plan.getToolName();
        Map<String, Object> args = plan.getToolArgs();

        return switch (tool) {
            case "add_int" -> {
                int a = (int) args.get("a");
                int b = (int) args.get("b");
                yield String.valueOf(numberFunction.add_int(a, b));
            }
            case "add_double" -> {
                double a = (double) args.get("a");
                double b = (double) args.get("b");
                yield String.valueOf(numberFunction.add_double(a, b));
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + tool);
        };
    }
}