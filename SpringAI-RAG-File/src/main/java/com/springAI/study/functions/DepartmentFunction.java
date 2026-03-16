package com.springAI.study.functions;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class DepartmentFunction {
    @Tool(
            name = "getEmployeeCount",
            description = "根据部门名称查询员工数量"
    )
    public int getEmployeeCount(@ToolParam(description = "部门名称")String department){
        if("研发部".equals(department)){
            return 30;
        }
        if("技术部".equals(department)){
            return 40;
        }
        return 0;
    }
}
