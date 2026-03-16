package com.springAI.study.functions;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class NumberFunction {

    @Tool(
            name = "add_int",
            description = "整数加法运算"
    )
    public int add_int(int a,int b){
        System.out.println("整数");
        return a+b;
    }

    @Tool(
            name = "add_float",
            description = "小数加法运算"
    )
    public double add_double(double a,double b){
        System.out.println("小数");
        return a+b;
    }
}
