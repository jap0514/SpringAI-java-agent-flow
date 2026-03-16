package com.springAI.study.model;

import lombok.Data;

import java.util.Map;

@Data
public class Plan {
    private boolean needRewrite;
    private boolean needRetrieval;
    private boolean needMemory;
    private boolean needTool;
    private boolean replan;
    private String retrievalQuery;

    private String toolName;
    private Map<String,Object> toolArgs;
}