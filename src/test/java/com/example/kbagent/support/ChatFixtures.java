package com.example.kbagent.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.example.kbagent.support.ApiClient.Response;
import com.example.kbagent.support.ApiClient.User;
import tools.jackson.databind.JsonNode;

/** 问答集成测试的公共准备步骤：建知识库、上传并等待入库完成、建会话。 */
public final class ChatFixtures {

    /** 一个已经 READY 的单文档知识库。chunks 按页码排列，每页一个 chunk。 */
    public record Kb(long kbId, long documentId, String filename, List<Chunk> chunks) {

        public Chunk page(int pageNumber) {
            return chunks.stream().filter(c -> c.pageNumber() == pageNumber).findFirst().orElseThrow();
        }

        public String[] contents() {
            return chunks.stream().map(Chunk::content).toArray(String[]::new);
        }
    }

    /** content 是入库时实际送去向量化的文本（与 doc_chunk.content 一致）。 */
    public record Chunk(int pageNumber, String content) {
    }

    private ChatFixtures() {
    }

    /** 上传一个 pages 页的 PDF（每页文本唯一，切分后每页一个 chunk），等待 READY 后读出 chunk 原文。 */
    public static Kb readyKnowledgeBase(ApiClient api, User owner, String visibility, int pages) {
        long kbId = api.createKnowledgeBase(owner, visibility);
        String token = UUID.randomUUID().toString().substring(0, 8);
        String[] texts = new String[pages];
        for (int i = 0; i < pages; i++) {
            texts[i] = ("Policy " + token + " section " + (i + 1) + ": expenses must be claimed within "
                    + (10 * (i + 1)) + " days after the trip. ").repeat(3);
        }
        String filename = "policy-" + token + ".pdf";
        Response upload = api.upload("/api/v1/knowledge-bases/" + kbId + "/documents", owner, filename,
                TestPdfs.withPages(texts));
        assertThat(upload.status()).as(upload.body().toString()).isEqualTo(202);
        long documentId = upload.data().path("id").asLong();
        api.awaitStatus(documentId, owner, "READY");
        JsonNode items = api.get("/api/v1/documents/" + documentId + "/chunks?page=1&size=100", owner)
                .data().path("items");
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            chunks.add(new Chunk(items.get(i).path("pageNumber").asInt(), items.get(i).path("content").asString()));
        }
        assertThat(chunks).extracting(Chunk::pageNumber)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, pages).boxed().toList());
        return new Kb(kbId, documentId, filename, chunks);
    }

    /** 创建 KB_QA 会话。kbId 为 null 时在用户有权访问的全部知识库中检索。 */
    public static long conversation(ApiClient api, User user, Long kbId) {
        Response created = api.post("/api/v1/conversations", user,
                "{\"mode\":\"KB_QA\"" + (kbId == null ? "" : ",\"kbId\":" + kbId) + "}");
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        return created.data().path("id").asLong();
    }

    public static String streamPath(long conversationId) {
        return "/api/v1/conversations/" + conversationId + "/messages/stream";
    }
}
