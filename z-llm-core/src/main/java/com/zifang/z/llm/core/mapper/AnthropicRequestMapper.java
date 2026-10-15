package com.zifang.z.llm.core.mapper;

import com.zifang.util.json.JsonCursor;
import com.zifang.util.json.JsonUtil;
import com.zifang.z.llm.api.dto.ContentPart;
import com.zifang.z.llm.api.dto.ToolSpec;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 协议 → UnifiedRequest mapper.
 *
 * <p>Anthropic 字段: { model, messages, system, max_tokens, temperature, top_p, stop_sequences,
 * stream, tools, tool_choice, metadata }.
 *
 * <p>system 字段在 Anthropic 是顶层字段 (不是 messages[0]), 转换时插入一条 role=system 的消息.
 */
public class AnthropicRequestMapper {

    public UnifiedRequest parse(JsonCursor root) {
        UnifiedRequest req = new UnifiedRequest();
        req.setModel(textOrNull(root, "model"));
        req.setMaxTokens(intOrNull(root, "max_tokens"));
        req.setTemperature(doubleOrNull(root, "temperature"));
        req.setTopP(doubleOrNull(root, "top_p"));
        req.setStream(boolOrNull(root, "stream"));

        JsonCursor stopSeq = root.path("stop_sequences");
        if (stopSeq.isArray()) {
            List<String> stops = new ArrayList<>();
            for (JsonCursor n : stopSeq.elements()) {
                stops.add(n.asText());
            }
            req.setStop(stops);
        }

        List<UnifiedMessage> msgs = new ArrayList<>();

        // system 字段 — string 或 [text_block] 数组
        JsonCursor system = root.path("system");
        if (!system.isMissing() && !system.isNull()) {
            UnifiedMessage sys = new UnifiedMessage();
            sys.setRole("system");
            if (isTextual(system)) {
                sys.setContent(system.asText());
            } else if (system.isArray()) {
                List<ContentPart> parts = new ArrayList<>();
                for (JsonCursor block : system.elements()) {
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

        JsonCursor msgArr = root.path("messages");
        if (msgArr.isArray()) {
            for (JsonCursor m : msgArr.elements()) {
                msgs.add(parseMessage(m));
            }
        }
        req.setMessages(msgs);

        JsonCursor toolsArr = root.path("tools");
        if (toolsArr.isArray()) {
            List<ToolSpec> tools = new ArrayList<>();
            for (JsonCursor t : toolsArr.elements()) {
                tools.add(parseTool(t));
            }
            req.setTools(tools);
        }

        JsonCursor tc = root.path("tool_choice");
        if (!tc.isMissing() && !tc.isNull()) {
            if (isTextual(tc)) {
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

    private UnifiedMessage parseMessage(JsonCursor m) {
        UnifiedMessage um = new UnifiedMessage();
        um.setRole(textOrNull(m, "role"));

        JsonCursor content = m.path("content");
        if (isTextual(content) || content.isNull()) {
            um.setContent(content.isNull() ? null : content.asText());
        } else if (content.isArray()) {
            List<ContentPart> parts = new ArrayList<>();
            List<com.zifang.z.llm.api.dto.ToolCall> tcs = new ArrayList<>();
            for (JsonCursor block : content.elements()) {
                String type = textOrNull(block, "type");
                if ("text".equals(type)) {
                    ContentPart p = new ContentPart();
                    p.setType("text");
                    p.setText(textOrNull(block, "text"));
                    parts.add(p);
                } else if ("image".equals(type)) {
                    // { type: image, source: { type: base64, media_type, data } }
                    JsonCursor source = block.path("source");
                    ContentPart p = new ContentPart();
                    p.setType("image_url");
                    ContentPart.ImageUrl img = new ContentPart.ImageUrl();
                    if (source.isObject() && "base64".equals(textOrNull(source, "type"))) {
                        String mt = textOrNull(source, "media_type");
                        String data = textOrNull(source, "data");
                        img.setUrl("data:" + mt + ";base64," + data);
                    } else if (source.isObject()) {
                        // source.type=url: 直接透传外链, 否则图片会以空 url 下发.
                        img.setUrl(textOrNull(source, "url"));
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
                    JsonCursor input = block.path("input");
                    fn.setArguments(input.isMissing() || input.isNull()
                            ? "{}" : JsonUtil.toJson(input.value()));
                    tc.setFunction(fn);
                    tcs.add(tc);
                } else if ("tool_result".equals(type)) {
                    UnifiedMessage tr = new UnifiedMessage();
                    tr.setRole("tool");
                    tr.setToolCallId(textOrNull(block, "tool_use_id"));
                    JsonCursor cc = block.path("content");
                    if (isTextual(cc)) {
                        tr.setContent(cc.asText());
                    } else {
                        // 多块内容, 用换行拼
                        StringBuilder sb = new StringBuilder();
                        for (JsonCursor b : cc.elements()) {
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

    private ToolSpec parseTool(JsonCursor t) {
        ToolSpec ts = new ToolSpec();
        ts.setType("function");
        ToolSpec.FunctionSpec fs = new ToolSpec.FunctionSpec();
        fs.setName(textOrNull(t, "name"));
        fs.setDescription(textOrNull(t, "description"));
        JsonCursor inputSchema = t.path("input_schema");
        if (!inputSchema.isMissing() && inputSchema.isObject()) {
            // 原来是 new ObjectMapper().convertValue(inputSchema, Map.class), 产出深展开的
            // LinkedHashMap; JsonUtil.parseToMap 是浅的, 这里必须用 toPlainValue.
            fs.setParameters(asStringMap(JsonUtil.toPlainValue(inputSchema.value())));
        }
        ts.setFunction(fs);
        return ts;
    }

    /** JsonCursor 没有 isTextual, 用底层值的类型判断. */
    private static boolean isTextual(JsonCursor n) {
        return n.isScalar() && n.value() instanceof String;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringMap(Object plain) {
        return (Map<String, Object>) plain;
    }

    private static String textOrNull(JsonCursor n, String f) {
        JsonCursor v = n.path(f);
        return v.isMissing() || v.isNull() ? null : v.asText();
    }

    private static Double doubleOrNull(JsonCursor n, String f) {
        JsonCursor v = n.path(f);
        return v.isMissing() || v.isNull() ? null : v.asDouble(0D);
    }

    private static Integer intOrNull(JsonCursor n, String f) {
        JsonCursor v = n.path(f);
        return v.isMissing() || v.isNull() ? null : v.asInt(0);
    }

    private static Boolean boolOrNull(JsonCursor n, String f) {
        JsonCursor v = n.path(f);
        return v.isMissing() || v.isNull() ? null : v.asBoolean(false);
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