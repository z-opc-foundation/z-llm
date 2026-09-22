package com.zifang.z.llm.core.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.zifang.z.llm.api.dto.ContentPart;
import com.zifang.z.llm.api.dto.ToolSpec;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Anthropic Messages API 协议 → UnifiedRequest mapper.
 *
 * <p>Anthropic 字段: { model, messages, system, max_tokens, temperature, top_p, stop_sequences,
 * stream, tools, tool_choice, metadata }.
 *
 * <p>system 字段在 Anthropic 是顶层字段 (不是 messages[0]), 转换时插入一条 role=system 的消息.
 */
public class AnthropicRequestMapper {

    public UnifiedRequest parse(JsonNode root) {
        UnifiedRequest req = new UnifiedRequest();
        req.setModel(textOrNull(root, "model"));
        req.setMaxTokens(intOrNull(root, "max_tokens"));
        req.setTemperature(doubleOrNull(root, "temperature"));
        req.setTopP(doubleOrNull(root, "top_p"));
        req.setStream(boolOrNull(root, "stream"));

        JsonNode stopSeq = root.path("stop_sequences");
        if (stopSeq.isArray()) {
            List<String> stops = new ArrayList<>();
            stopSeq.forEach(n -> stops.add(n.asText()));
            req.setStop(stops);
        }

        List<UnifiedMessage> msgs = new ArrayList<>();

        // system 字段 — string 或 [text_block] 数组
        JsonNode system = root.path("system");
        if (!system.isMissingNode() && !system.isNull()) {
            UnifiedMessage sys = new UnifiedMessage();
            sys.setRole("system");
            if (system.isTextual()) {
                sys.setContent(system.asText());
            } else if (system.isArray()) {
                List<ContentPart> parts = new ArrayList<>();
                for (Iterator<JsonNode> it = system.elements(); it.hasNext(); ) {
                    JsonNode block = it.next();
                    if ("text".equals(textOrNull(block, "type"))) {
                        ContentPart p = new ContentPart();
                        p.setType("text");
                        p.setText(textOrNull(block, "text"));
                        parts.add(p);
                    }
                }
                sys.setContents(parts);
            }
            msgs.add(sys);
        }

        JsonNode msgArr = root.path("messages");
        if (msgArr.isArray()) {
            for (Iterator<JsonNode> it = msgArr.elements(); it.hasNext(); ) {
                msgs.add(parseMessage(it.next()));
            }
        }
        req.setMessages(msgs);

        JsonNode toolsArr = root.path("tools");
        if (toolsArr.isArray()) {
            List<ToolSpec> tools = new ArrayList<>();
            for (Iterator<JsonNode> it = toolsArr.elements(); it.hasNext(); ) {
                tools.add(parseTool(it.next()));
            }
            req.setTools(tools);
        }

        JsonNode tc = root.path("tool_choice");
        if (!tc.isMissingNode() && !tc.isNull()) {
            if (tc.isTextual()) {
                req.setToolChoice(tc.asText());
            } else if (tc.isObject()) {
                String type = textOrNull(tc, "type");
                if ("tool".equals(type)) {
                    // Anthropic 协议: name 是 tc 的兄弟字段, 不是嵌套
                    String name = textOrNull(tc, "name");
                    req.setToolChoice(new AnthropicToolChoiceRef("tool", name));
                }
            }
        }

        return req;
    }

    private UnifiedMessage parseMessage(JsonNode m) {
        UnifiedMessage um = new UnifiedMessage();
        um.setRole(textOrNull(m, "role"));

        JsonNode content = m.path("content");
        if (content.isTextual() || content.isNull()) {
            um.setContent(content.isNull() ? null : content.asText());
        } else if (content.isArray()) {
            List<ContentPart> parts = new ArrayList<>();
            List<com.zifang.z.llm.api.dto.ToolCall> tcs = new ArrayList<>();
            for (Iterator<JsonNode> it = content.elements(); it.hasNext(); ) {
                JsonNode block = it.next();
                String type = textOrNull(block, "type");
                if ("text".equals(type)) {
                    ContentPart p = new ContentPart();
                    p.setType("text");
                    p.setText(textOrNull(block, "text"));
                    parts.add(p);
                } else if ("image".equals(type)) {
                    // { type: image, source: { type: base64, media_type, data } }
                    JsonNode source = block.path("source");
                    ContentPart p = new ContentPart();
                    p.setType("image_url");
                    ContentPart.ImageUrl img = new ContentPart.ImageUrl();
                    if (source.isObject() && "base64".equals(textOrNull(source, "type"))) {
                        String mt = textOrNull(source, "media_type");
                        String data = textOrNull(source, "data");
                        img.setUrl("data:" + mt + ";base64," + data);
                    }
                    p.setImageUrl(img);
                    parts.add(p);
                } else if ("tool_use".equals(type)) {
                    com.zifang.z.llm.api.dto.ToolCall tc = new com.zifang.z.llm.api.dto.ToolCall();
                    tc.setId(textOrNull(block, "id"));
                    tc.setType("function");
                    com.zifang.z.llm.api.dto.ToolCall.FunctionCall fn =
                            new com.zifang.z.llm.api.dto.ToolCall.FunctionCall();
                    fn.setName(textOrNull(block, "name"));
                    JsonNode input = block.path("input");
                    fn.setArguments(input.isMissingNode() || input.isNull()
                            ? "{}" : input.toString());
                    tc.setFunction(fn);
                    tcs.add(tc);
                } else if ("tool_result".equals(type)) {
                    UnifiedMessage tr = new UnifiedMessage();
                    tr.setRole("tool");
                    tr.setToolCallId(textOrNull(block, "tool_use_id"));
                    JsonNode cc = block.path("content");
                    if (cc.isTextual()) {
                        tr.setContent(cc.asText());
                    } else {
                        // 多块内容, 用换行拼
                        StringBuilder sb = new StringBuilder();
                        for (Iterator<JsonNode> cit = cc.elements(); cit.hasNext(); ) {
                            JsonNode b = cit.next();
                            if ("text".equals(textOrNull(b, "type"))) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(textOrNull(b, "text"));
                            }
                        }
                        tr.setContent(sb.toString());
                    }
                    return tr;
                }
            }
            if (!parts.isEmpty()) um.setContents(parts);
            if (!tcs.isEmpty()) um.setToolCalls(tcs);
        }
        return um;
    }

    private ToolSpec parseTool(JsonNode t) {
        ToolSpec ts = new ToolSpec();
        ts.setType("function");
        ToolSpec.FunctionSpec fs = new ToolSpec.FunctionSpec();
        fs.setName(textOrNull(t, "name"));
        fs.setDescription(textOrNull(t, "description"));
        JsonNode inputSchema = t.path("input_schema");
        if (!inputSchema.isMissingNode() && inputSchema.isObject()) {
            fs.setParameters(new com.fasterxml.jackson.databind.ObjectMapper()
                    .convertValue(inputSchema, java.util.Map.class));
        }
        ts.setFunction(fs);
        return ts;
    }

    private static String textOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Double doubleOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asDouble();
    }

    private static Integer intOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static Boolean boolOrNull(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asBoolean();
    }

    /** Anthropic tool_choice.type=tool + name 的轻量表示. */
    public static class AnthropicToolChoiceRef {
        private final String type;
        private final String name;

        public AnthropicToolChoiceRef(String type, String name) {
            this.type = type;
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public String getName() {
            return name;
        }
    }
}