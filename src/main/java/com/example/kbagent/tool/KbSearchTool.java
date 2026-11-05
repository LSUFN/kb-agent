package com.example.kbagent.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.example.kbagent.business.service.DataScope.ScopeDeniedException;
import com.example.kbagent.observability.Sensitive;
import com.example.kbagent.retrieval.service.RetrievalService;
import com.example.kbagent.retrieval.service.RetrievalService.CallContext;
import com.example.kbagent.retrieval.service.RetrievalService.Candidate;
import com.example.kbagent.user.domain.Region;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * searchKnowledgeBase：在用户有权访问的知识库中检索（所有角色可用）。
 * 结果按本轮的 {@link CitationRegistry} 全局编号，模型引用时使用返回的 index。
 */
@Component
public class KbSearchTool implements AgentTool<KbSearchTool.Args> {

    public record Args(
            @ToolParam(description = "检索用的问题或关键词，使用完整的自然语言句子效果更好")
            @NotBlank @Size(max = 500) @Sensitive String query,
            @ToolParam(required = false, description = "只在这个知识库中检索；不传则检索全部可访问的知识库")
            Long kbId) {
    }

    /** 返回给模型的一个片段。 */
    public record Hit(int index, String filename, int page, String content) {
    }

    private final RetrievalService retrieval;

    public KbSearchTool(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    @Override
    public String name() {
        return ToolPolicy.SEARCH_KNOWLEDGE_BASE;
    }

    @Override
    public String description() {
        return "在企业知识库（制度、手册等文档）中检索与问题相关的片段。回答涉及公司制度或文档内容时使用；"
                + "引用检索结果时，在句末用方括号标注片段的 index，例如 [4]。";
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public Set<Region> narrow(Args args, AgentRequestContext ctx) {
        if (args.kbId() != null && !ctx.accessibleKbIds().contains(args.kbId())) {
            throw new ScopeDeniedException("无权访问知识库 " + args.kbId());
        }
        return Set.of();
    }

    @Override
    public Output execute(Args args, Set<Region> scope, AgentRequestContext ctx) {
        CallContext call = new CallContext(ctx.traceId(), ctx.conversationId());
        List<Candidate> found = args.kbId() != null
                ? retrieval.retrieve(ctx.actor(), args.kbId(), args.query(), call)
                : retrieval.retrieveIn(ctx.actor(), ctx.accessibleKbIds(), args.query(), call);
        List<Candidate> numbered = ctx.citations().register(found);
        List<Hit> hits = numbered.stream()
                .map(c -> new Hit(c.index(), c.filename(), c.pageNumber(), c.content().strip()))
                .toList();
        if (hits.isEmpty()) {
            return new Output(Map.of("results", List.of(), "message", "知识库中没有找到相关内容"), "没有命中");
        }
        // 引用要求放在工具结果里再说一次：工具结果离模型的回答最近，比 System Prompt 更有效（M4 实测）
        return new Output(Map.of("results", hits, "note", "回答中引用这些片段时，在句末标注 index，例如 ["
                + hits.getFirst().index() + "]；不要使用原文中的条款号或页码（如“第九条”不能写成 [9]）"),
                "命中 " + hits.size() + " 个片段");
    }
}
