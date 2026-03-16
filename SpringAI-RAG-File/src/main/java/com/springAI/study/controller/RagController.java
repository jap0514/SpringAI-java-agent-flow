package com.springAI.study.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.springAI.study.ingestion.IngestionService;
import com.springAI.study.mcp.MCP;
import com.springAI.study.model.ChatMemory;
import com.springAI.study.rag.RagService;
import com.springAI.study.rag.RagService_old;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/rag")
@CrossOrigin(origins = "*")  //允许前端跨域访问
public class RagController {

    @Autowired
    private RagService ragService;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private MCP mcp;

    private ChatMemory chatMemory=new ChatMemory();


    @GetMapping("/query")
    public String query(@RequestParam String question) throws JsonProcessingException {

        //return ragService.queryWithPlannerAndReflection(question,chatMemory);
        return mcp.handleQuestion(question,chatMemory);
    }

    //文件上传
    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("请选择一个文件上传");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return ResponseEntity.badRequest().body("文件名无效");
        }

        // 获取文件扩展名并校验
        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!extension.equals("pdf") && !extension.equals("txt")) {
            return ResponseEntity.badRequest().body("仅支持 PDF 或 TXT 文件");
        }

        // 可选：根据 MIME 类型进一步校验（但不能完全依赖）
        String contentType = file.getContentType();
        if (extension.equals("pdf") && !"application/pdf".equals(contentType)) {
            // 只是警告，不阻止上传
            System.out.println("警告：文件扩展名为 PDF 但 MIME 类型为 " + contentType);
        }

        //版本
        //String version = UUID.randomUUID().toString();
        //用时间戳做版本
        DateTimeFormatter formatter=DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
        String version_time= LocalDateTime.now().format(formatter);
        Path tempFilePath = null;
        Path tempDirPath = null;

        try {
            tempDirPath = Files.createTempDirectory("rag-upload");
            tempFilePath = tempDirPath.resolve(originalFilename);
            file.transferTo(tempFilePath.toFile());

            // 调用通用文档入库服务
            ingestionService.ingestFile(tempFilePath.toFile(), version_time);

            return ResponseEntity.ok("文档上传并处理成功，版本号: " + version_time);

        } catch (IOException e) {
            e.printStackTrace(); // 实际项目使用日志
            String errorMessage = e.getMessage();
            if (errorMessage.contains("不支持的文件类型") || errorMessage.contains("PDF 解析失败") || errorMessage.contains("End-of-File")) {
                return ResponseEntity.status(400).body("文件处理失败：" + errorMessage);
            }
            return ResponseEntity.status(500).body("文件处理失败: " + errorMessage);
        } finally {
            // 清理临时文件
            try {
                if (tempFilePath != null && Files.exists(tempFilePath)) {
                    Files.deleteIfExists(tempFilePath);
                }
                if (tempDirPath != null && Files.exists(tempDirPath)) {
                    Files.deleteIfExists(tempDirPath);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // 工具方法：获取文件扩展名
    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1);
    }
}
