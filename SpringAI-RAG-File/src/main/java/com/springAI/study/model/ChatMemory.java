package com.springAI.study.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Collectors;

public class ChatMemory {
    //存储每一轮对话
    private final Deque<ChatTurn> chatTurns=new ArrayDeque<>();
    //最大存储轮数
    private final Integer maxTurns=5;

    public void addTurns(String question,String answer){
        if(chatTurns.size()>=maxTurns){
            chatTurns.pollFirst();
        }
        chatTurns.addLast(new ChatTurn(question,answer));
    }

    public String GetHistoryContext(){
        return chatTurns.stream()
                .map(turn->"question: "+turn.getQuestion()+"\nModelAnswer: "+turn.getModelAnswer())
                .collect(Collectors.joining("\n"));
    }

}
