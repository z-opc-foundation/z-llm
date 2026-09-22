package com.zifang.z.llm.core.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.llm.api.dto.ContentPart;
import com.zifang.z.llm.api.dto.ToolSpec;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;

import java.util.ArrayList;
import java.util.Iterator;
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

    private final ObjectMapper json;

    public OpenAIRequestMapper(ObjectMapper json) {
        this.json = json;
    }

    public UnifiedRequest parse(JsonNode root) {
        UnifiedRequest req = new UnifiedRequest();
        req.setModel(textOrNull(root, "model"));
        req.setTemperature(doubleOrNull(root, "temperature"));
        req.setTopP(doubleOrNull(root, "top_p"));
        req.setMaxTokens(intOrNull(root, "max_tokens"));
        req.setStream(boolOrNull(root, "stream"));
        req.setFrequencyPenalty(doubleOrNull(root, "frequency_penalty"));
        req.setPresencePenalty(doubleOrNull(root, "presence_penalty"));
        req.setUser(textOrNull(root, "user"));

        JsonNode stopNode = root.path("stop");
        if (stopNode.isArray()) {
            List<String> stops = new ArrayList<>();
            stopNode.forEach(n -> stops.add(n.asText()));
            req.setStop(stops);
        } else if (stopNode.isTextual()) {
            List<String> stops = new ArrayList<>();
            stops.add(stopNode.asText());
            req.setStop(stops);
        }

        JsonNode msgArr = root.path("messages");
        if (msgArr.isArray()) {
            List<UnifiedMessage> msgs = new ArrayList<>();
            for (Iterator<JsonNode> it = msgArr.elements(); it.hasNext(); ) {
                msgs.add(parseMessage(it.next()));
            }
            req.setMessages(msgs);
        }

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
            } else {
                req.setToolChoice(json.convertValue(tc, Object.class));
            }
        }

        return req;
    }

    private UnifiedMessage parseMessage(JsonNode m) {
        UnifiedMessage um = new UnifiedMessage();
        um.setRole(textOrNull(m, "role"));
        um.setName(textOrNull(m, "name"));
        um.setToolCallId(textOrNull(m, "tool_call_id"));

        JsonNode content = m.path("content");
        if (content.isTextual() || content.isNull()) {
            um.setContent(content.isNull() ? null : content.asText());
        } else if (content.isArray()) {
            List<ContentPart> parts = new ArrayList<>();
            for (Iterator<JsonNode> it = content.elements(); it.hasNext(); ) {
                JsonNode p = it.next();
                ContentPart cp = new ContentPart();
                cp.setType(textOrNull(p, "type"));
                cp.setText(textOrNull(p, "text"));
                JsonNode iu = p.path("image_url");
                if (!iu.isMissingNode() && !iu.isNull()) {
                    ContentPart.ImageUrl img = new ContentPart.ImageUrl();
                    img.setUrl(textOrNull(iu, "url"));
                    img.setDetail(textOrNull(iu, "detail"));
                    cp.setImageUrl(img);
                }
                parts.add(cp);
            }
            um.setContents(parts);
        }

        JsonNode tcArr = m.path("tool_calls");
        if (tcArr.isArray() && tcArr.size() > 0) {
            List<com.zifang.z.llm.api.dto.ToolCall> tcs = new ArrayList<>();
            for (Iterator<JsonNode> it = tcArr.elements(); it.hasNext(); ) {
                tcs.add(parseToolCall(it.next()));
            }
            um.setToolCalls(tcs);
        }
        return um;
    }

    private com.zifang.z.llm.api.dto.ToolCall parseToolCall(JsonNode t) {
        com.zifang.z.llm.api.dto.ToolCall tc = new com.zifang.z.llm.api.dto.ToolCall();
        tc.setId(textOrNull(t, "id"));
        tc.setType(textOrNull(t, "type"));
        JsonNode fn = t.path("function");
        if (!fn.isMissingNode() && !fn.isNull()) {
            com.zifang.z.llm.api.dto.ToolCall.FunctionCall f = new com.zifang.z.llm.api.dto.ToolCall.FunctionCall();
            f.setName(textOrNull(fn, "name"));
            f.setArguments(textOrNull(fn, "arguments"));
            tc.setFunction(f);
        }
        return tc;
    }

    private ToolSpec parseTool(JsonNode t) {
        ToolSpec ts = new ToolSpec();
        ts.setType(textOrNull(t, "type"));
        JsonNode fn = t.path("function");
        if (!fn.isMissingNode() && !fn.isNull()) {
            ToolSpec.FunctionSpec fs = new ToolSpec.FunctionSpec();
            fs.setName(textOrNull(fn, "name"));
            fs.setDescription(textOrNull(fn, "description"));
            JsonNode params = fn.path("parameters");
            if (!params.isMissingNode() && params.isObject()) {
                fs.setParameters(json.convertValue(params, Map.class));
            }
            ts.setFunction(fs);
        }
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
}