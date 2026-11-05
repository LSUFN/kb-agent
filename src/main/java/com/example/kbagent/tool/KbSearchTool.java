package com.example.kbagent.tool;

import java.util.List;
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
 * 结果按本轮的 {@link CitationRegistry} 全局编号，模型引用时使用片段前面的序号。
 * <p>
 * 返回给模型的是纯文本，格式与 KB_QA 的参考资料相同（“[n] 《文件》第 p 页”，引用要求放在最后）。
 * M4 实测：返回 JSON、把引用提醒放在一个字段里时，qwen-plus 仍有约一半的回答把原文的“第八条”写成 [8]；
 * 同样的问题在 KB_QA 的格式下 15 次全部正确。
 */
@Component
public class KbSearchTool implements AgentTool<KbSearchTool.Args> {

    public record Args(
            @ToolParam(description = "检索用的问题或关键词，使用完整的自然语言句子效果更好")
            @NotBlank @Size(max = 500) @Sensitive String query,
            @ToolParam(required = false, description = "只在这个知识库中检索；不传则检索全部可访问的知识库")
            Long kbId) {
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
        if (numbered.isEmpty()) {
            return new Output("【检索结果】知识库中没有找到相关内容。", "没有命中");
        }
        return new Output(render(numbered), "命中 " + numbered.size() + " 个片段");
    }

    /** 与 KB_QA 的参考资料格式一致；引用要求放在最后，离模型的回答最近。 */
    static String render(List<Candidate> numbered) {
        StringBuilder text = new StringBuilder("【检索结果】（共 ").append(numbered.size()).append(" 条）\n");
        for (Candidate c : numbered) {
            text.append('[').append(c.index()).append("] 《").append(c.filename()).append("》第 ")
                    .append(c.pageNumber()).append(" 页\n")
                    .append(c.content().strip()).append("\n\n");
        }
        String example = "[" + numbered.getFirst().index() + "]";
        return text.append("【引用要求】引用以上片段时，在句末标注片段前面的序号，例如 ").append(example)
                .append("。方括号里只能写这个序号，不要写原文中的条款号或页码（例如“第九条”不能写成 [9]）。")
                .toString();
    }
}
