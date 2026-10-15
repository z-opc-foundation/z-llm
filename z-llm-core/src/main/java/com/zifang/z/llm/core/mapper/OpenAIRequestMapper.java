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
 * OpenAI ChatCompletions 协议 → UnifiedRequest mapper.
 *
 * <p>把 { model, messages, temperature, top_p, max_tokens, stream, tools, tool_choice, stop,
 * frequency_penalty, presence_penalty, user } 转成平台无关的 UnifiedRequest.
 *
 * <p>支持多模态 messages (content 是数组场景).
 */
public class OpenAIRequestMapper {

    public UnifiedRequest parse(JsonCursor root) {
        UnifiedRequest req = new UnifiedRequest();
        req.setModel(textOrNull(root, "model"));
        req.setTemperature(doubleOrNull(root, "temperature"));
        req.setTopP(doubleOrNull(root, "top_p"));
        req.setMaxTokens(intOrNull(root, "max_tokens"));
        req.setStream(boolOrNull(root, "stream"));
        req.setFrequencyPenalty(doubleOrNull(root, "frequency_penalty"));
        req.setPresencePenalty(doubleOrNull(root, "presence_penalty"));
        req.setUser(textOrNull(root, "user"));

        JsonCursor stopNode = root.path("stop");
        if (stopNode.isArray()) {
            List<String> stops = new ArrayList<>();
            for (JsonCursor n : stopNode.elements()) {
                stops.add(n.asText());
            }
            req.setStop(stops);
        } else if (isTextual(stopNode)) {
            List<String> stops = new ArrayList<>();
            stops.add(stopNode.asText());
            req.setStop(stops);
        }

        JsonCursor msgArr = root.path("messages");
        if (msgArr.isArray()) {
            List<UnifiedMessage> msgs = new ArrayList<>();
            for (JsonCursor m : msgArr.elements()) {
                msgs.add(parseMessage(m));
            }
            req.setMessages(msgs);
        }

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
            } else {
                // 原来走的是 convertValue(tc, Object.class), 目标是 Object 就等于把节点转成
                // 普通 Map/List, 所以这里直接深展开, 不能给 JsonUtil.toPlainValue 之外的浅层结果.
                req.setToolChoice(JsonUtil.toPlainValue(tc.value()));
            }
        }

        return req;
    }

    private UnifiedMessage parseMessage(JsonCursor m) {
        UnifiedMessage um = new UnifiedMessage();
        um.setRole(textOrNull(m, "role"));
        um.setName(textOrNull(m, "name"));
        um.setToolCallId(textOrNull(m, "tool_call_id"));

        JsonCursor content = m.path("content");
        if (isTextual(content) || content.isNull()) {
            um.setContent(content.isNull() ? null : content.asText());
        } else if (content.isArray()) {
            List<ContentPart> parts = new ArrayList<>();
            for (JsonCursor p : content.elements()) {
                ContentPart cp = new ContentPart();
                cp.setType(textOrNull(p, "type"));
                cp.setText(textOrNull(p, "text"));
                JsonCursor iu = p.path("image_url");
                if (!iu.isMissing() && !iu.isNull()) {
                    ContentPart.ImageUrl img = new ContentPart.ImageUrl();
                    img.setUrl(textOrNull(iu, "url"));
                    img.setDetail(textOrNull(iu, "detail"));
                    cp.setImageUrl(img);
                }
                parts.add(cp);
            }
            um.setContents(parts);
        }

        JsonCursor tcArr = m.path("tool_calls");
        if (tcArr.isArray() && tcArr.size() > 0) {
            List<com.zifang.z.llm.api.dto.ToolCall> tcs = new ArrayList<>();
            for (JsonCursor t : tcArr.elements()) {
                tcs.add(parseToolCall(t));
            }
            um.setToolCalls(tcs);
        }
        return um;
    }

    private com.zifang.z.llm.api.dto.ToolCall parseToolCall(JsonCursor t) {
        com.zifang.z.llm.api.dto.ToolCall tc = new com.zifang.z.llm.api.dto.ToolCall();
        tc.setId(textOrNull(t, "id"));
        tc.setType(textOrNull(t, "type"));
        JsonCursor fn = t.path("function");
        if (!fn.isMissing() && !fn.isNull()) {
            com.zifang.z.llm.api.dto.ToolCall.FunctionCall f = new com.zifang.z.llm.api.dto.ToolCall.FunctionCall();
            f.setName(textOrNull(fn, "name"));
            f.setArguments(textOrNull(fn, "arguments"));
            tc.setFunction(f);
        }
        return tc;
    }

    private ToolSpec parseTool(JsonCursor t) {
        ToolSpec ts = new ToolSpec();
        ts.setType(textOrNull(t, "type"));
        JsonCursor fn = t.path("function");
        if (!fn.isMissing() && !fn.isNull()) {
            ToolSpec.FunctionSpec fs = new ToolSpec.FunctionSpec();
            fs.setName(textOrNull(fn, "name"));
            fs.setDescription(textOrNull(fn, "description"));
            JsonCursor params = fn.path("parameters");
            if (!params.isMissing() && params.isObject()) {
                // 原来的 convertValue(params, Map.class) 产出的是深展开的 LinkedHashMap,
                // 而 JsonUtil.parseToMap 是浅的 (嵌套值仍是 JsonObject), 所以必须用 toPlainValue.
                fs.setParameters(asStringMap(JsonUtil.toPlainValue(params.value())));
            }
            ts.setFunction(fs);
        }
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
}
