-- V3：保存 AGENT 回答的数字核对结果（ARCHITECTURE.md §4.11）。
-- JSON 数组，列出回答中找不到出处的数字；KB_QA 回答和 V3 之前的回答为 NULL。
-- 组装对话历史时会跳过含有未核实数字的轮次：实测模型会照着历史里自己编造的数字原样再说一遍。
ALTER TABLE chat_message ADD COLUMN unverified_numbers JSONB;
