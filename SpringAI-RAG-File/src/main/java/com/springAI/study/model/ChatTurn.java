package com.springAI.study.model;

import lombok.Data;

//如果没有 final 字段：@Data 不会生成任何构造函数（只生成默认的无参构造，前提是类中没有其他构造函数）。
@Data
public class ChatTurn {
    private final String question;
    private final String ModelAnswer;
}
