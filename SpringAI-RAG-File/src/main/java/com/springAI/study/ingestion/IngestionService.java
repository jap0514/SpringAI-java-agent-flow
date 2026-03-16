package com.springAI.study.ingestion;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IngestionService {
    //向量模型
    private final EmbeddingModel embeddingModel;
    //向量存储
    private final VectorStore vectorStore;

    public IngestionService(EmbeddingModel embeddingModel,VectorStore vectorStore){
        this.embeddingModel=embeddingModel;
        this.vectorStore=vectorStore;
    }

    //PDF文本提取
    public String extractTextFromPDF(File file) throws IOException{
        try(PDDocument document=PDDocument.load(file)){
            PDFTextStripper stripper=new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    // TXT 文本提取（使用 UTF-8 编码）
    public String extractTextFromTxt(File file) throws IOException {
        return Files.readString(file.toPath()); // 默认 UTF-8
    }

    //文本拆分成 chunk
    public List<String> chunkText(String text,int chunkSize,int overlap){
        List<String> chunks=new ArrayList<>();
        int start=0;
        while(start<text.length()){
            int end=Math.min(start+chunkSize,text.length());
            chunks.add(text.substring(start,end));
            start+=(chunkSize-overlap);
        }
        return chunks;
    }


    // 通用文档入库方法（根据文件扩展名自动选择解析器）
    public void ingestFile(File file, String version) throws IOException {
        String fileName = file.getName();
        String extension = getFileExtension(fileName).toLowerCase();

        String text;
        switch (extension) {
            case "pdf":
                text = extractTextFromPDF(file);
                break;
            case "txt":
                text = extractTextFromTxt(file);
                break;
            default:
                throw new IOException("不支持的文件类型: " + extension + "，目前仅支持 PDF 和 TXT");
        }

        // 如果提取的文本为空，则警告或跳过
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("文件内容为空或无法提取文本");
        }

        List<String> chunks = chunkText(text, 500, 50);
        List<Document> documents = new ArrayList<>();
        int idx = 1;
        for (String chunk : chunks) {

            //对chunk 内容做 hash
            //String chunkHash=sha256(chunk);   用hash想做documentId是行不通的
            //构造稳定 documentId
            //Qdrant 的底层存储 / 索引对 ID 类型有限制
            //Spring AI 为了统一接口，只开放 UUID 路径
            //UUID：
            //固定长度
            //可索引
            //不会爆内存
            //高效序列化
            UUID documentId=stableUuidFromContent(fileName+"::"+chunk);


            //metadata
            Map<String, Object> meta = Map.of(
                    "source", fileName,
                    "type", extension,
                    "chunk", idx,
                    "version", version
            );
            //带 ID 的document
            Document document=new Document(documentId.toString(),chunk,meta);
            documents.add(document);
            idx++;
        }

        //upsert 写入（如果相同 ID 会覆盖）
        vectorStore.add(documents);
    }

    // 保留原有的 ingestPDF 方法（可选，用于向后兼容）
    public void ingestPDF(File pdfFile, String version) throws IOException {
        ingestFile(pdfFile, version); // 直接调用通用方法
    }

    // 工具方法：获取文件扩展名
    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1);
    }

    private UUID stableUuidFromContent(String content){
        //通过 content 的bytes 来确定唯一的UUID
        return UUID.nameUUIDFromBytes(
                content.getBytes(StandardCharsets.UTF_8)
        );
    }

    //内容哈希，用来防止一样的数据造成向量数据库冗余
    private String sha256(String text){
        try{
            //创建一个使用 “SHA-256” 算法的 MessageDigest 对象
            MessageDigest digest=MessageDigest.getInstance("SHA-256");

            //将输入字符串按 UTF-8 编码转为字节数组。
            //调用 digest() 方法一次性完成哈希计算，返回 32 字节（256 位）的原始哈希值。
            byte[] hashBytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex=new StringBuilder();
            for(byte b:hashBytes){
                hex.append(String.format("%02x",b));
            }
            return hex.toString();
        }catch (Exception e){
            throw new RuntimeException("计算hash失败",e);
        }
    }

}
