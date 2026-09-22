package com.zifang.z.llm.core.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.llm.api.dto.ContentPart;
import com.zifang.z.llm.api.dto.ToolSpec;
import com.zifang.z.llm.api.dto.UnifiedMessage;
import com.zifang.z.llm.api.dto.UnifiedRequest;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class AnthropicRequestMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AnthropicRequestMapper mapper = new AnthropicRequestMapper();

    @Test
    public void parse_simple_messages_with_system() throws Exception {
        String body = "{\n" +
                "  \"model\": \"claude-3-5-sonnet-latest\",\n" +
                "  \"system\": \"You are a helpful assistant.\",\n" +
                "  \"max_tokens\": 1024,\n" +
                "  \"messages\": [\n" +
                "    {\"role\":\"user\",\"content\":\"hi\"}\n" +
                "  ]\n" +
                "}";
        JsonNode root = json.readTree(body);
        UnifiedRequest req = mapper.parse(root);

        assertEquals("claude-3-5-sonnet-latest", req.getModel());
        assertEquals(Integer.valueOf(1024), req.getMaxTokens());
        List<UnifiedMessage> msgs = req.getMessages();
        assertEquals(2, msgs.size());
        // system 应作为第一条消息插入
        assertEquals("system", msgs.get(0).getRole());
        assertEquals("You are a helpful assistant.", msgs.get(0).getContent());
        assertEquals("user", msgs.get(1).getRole());
        assertEquals("hi", msgs.get(1).getContent());
    }

    @Test
    public void parse_tool_use_in_assistant_message() throws Exception {
        String body = "{\n" +
                "  \"model\": \"claude-3-5-sonnet-latest\",\n" +
                "  \"max_tokens\": 1024,\n" +
                "  \"messages\": [\n" +
                "    {\"role\":\"user\",\"content\":\"北京天气\"},\n" +
                "    {\"role\":\"assistant\",\"content\":[\n" +
                "      {\"type\":\"text\",\"text\":\"让我查一下\"},\n" +
                "      {\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Beijing\"}}\n" +
                "    ]},\n" +
                "    {\"role\":\"user\",\"content\":[\n" +
                "      {\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"晴 22度\"}\n" +
                "    ]}\n" +
                "  ],\n" +
                "  \"tools\": [\n" +
                "    {\"name\":\"get_weather\",\"description\":\"get weather\",\"input_schema\":{\"type\":\"object\"}}\n" +
                "  ]\n" +
                "}";
        UnifiedRequest req = mapper.parse(json.readTree(body));
        List<UnifiedMessage> msgs = req.getMessages();
        assertEquals(3, msgs.size());

        // assistant 消息: text + tool_use
        UnifiedMessage asst = msgs.get(1);
        assertEquals("assistant", asst.getRole());
        assertNotNull(asst.getContents());
        // 第一个 text block 落入 contents, tool_use 落入 toolCalls
        assertEquals(1, asst.getContents().size());
        assertEquals("text", asst.getContents().get(0).getType());
        assertEquals("让我查一下", asst.getContents().get(0).getText());
        assertEquals(1, asst.getToolCalls().size());
        assertEquals("toolu_1", asst.getToolCalls().get(0).getId());
        assertEquals("get_weather", asst.getToolCalls().get(0).getFunction().getName());
        assertTrue(asst.getToolCalls().get(0).getFunction().getArguments().contains("Beijing"));

        // tool_result 消息: 应转为 role=tool
        UnifiedMessage toolRes = msgs.get(2);
        assertEquals("tool", toolRes.getRole());
        assertEquals("toolu_1", toolRes.getToolCallId());
        assertEquals("晴 22度", toolRes.getContent());

        // tools: input_schema → parameters
        List<ToolSpec> tools = req.getTools();
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).getType());
        assertEquals("get_weather", tools.get(0).getFunction().getName());
        assertNotNull(tools.get(0).getFunction().getParameters());
    }

    @Test
    public void parse_image_base64() throws Exception {
        String body = "{\n" +
                "  \"model\": \"claude-3-5-sonnet-latest\",\n" +
                "  \"max_tokens\": 1024,\n" +
                "  \"messages\": [\n" +
                "    {\"role\":\"user\",\"content\":[\n" +
                "      {\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KG=\"}}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}";
        UnifiedRequest req = mapper.parse(json.readTree(body));
        UnifiedMessage m = req.getMessages().get(0);
        assertEquals(1, m.getContents().size());
        ContentPart p = m.getContents().get(0);
        assertEquals("image_url", p.getType());
        assertTrue(p.getImageUrl().getUrl().startsWith("data:image/png;base64,"));
    }

    @Test
    public void parse_tool_choice_tool() throws Exception {
        String body = "{\n" +
                "  \"model\": \"claude-3-5-sonnet-latest\",\n" +
                "  \"max_tokens\": 1024,\n" +
                "  \"messages\": [{\"role\":\"user\",\"content\":\"hi\"}],\n" +
                "  \"tool_choice\": {\"type\":\"tool\",\"name\":\"get_weather\"}\n" +
                "}";
        UnifiedRequest req = mapper.parse(json.readTree(body));
        assertNotNull(req.getToolChoice());
        assertTrue(req.getToolChoice() instanceof AnthropicRequestMapper.AnthropicToolChoiceRef);
        AnthropicRequestMapper.AnthropicToolChoiceRef ref =
                (AnthropicRequestMapper.AnthropicToolChoiceRef) req.getToolChoice();
        assertEquals("tool", ref.getType());
        assertEquals("get_weather", ref.getName());
    }

    @Test
    public void parse_stop_sequences() throws Exception {
        String body = "{\n" +
                "  \"model\": \"claude-3-5-sonnet-latest\",\n" +
                "  \"max_tokens\": 1024,\n" +
                "  \"messages\": [{\"role\":\"user\",\"content\":\"hi\"}],\n" +
                "  \"stop_sequences\": [\"END\", \"STOP\"]\n" +
                "}";
        UnifiedRequest req = mapper.parse(json.readTree(body));
        assertEquals(2, req.getStop().size());
        assertEquals("END", req.getStop().get(0));
    }
}