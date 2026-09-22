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

public class OpenAIRequestMapperTest {

    private final ObjectMapper json = new ObjectMapper();
    private final OpenAIRequestMapper mapper = new OpenAIRequestMapper(json);

    @Test
    public void parse_simple_text_chat() throws Exception {
        String body = "{\n" +
                "  \"model\": \"openai/gpt-4o\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\":\"system\",\"content\":\"You are a helpful assistant.\"},\n" +
                "    {\"role\":\"user\",\"content\":\"Hello\"}\n" +
                "  ],\n" +
                "  \"temperature\": 0.5,\n" +
                "  \"max_tokens\": 1024,\n" +
                "  \"stream\": false\n" +
                "}";
        JsonNode root = json.readTree(body);
        UnifiedRequest req = mapper.parse(root);

        assertEquals("openai/gpt-4o", req.getModel());
        assertEquals(Double.valueOf(0.5), req.getTemperature());
        assertEquals(Integer.valueOf(1024), req.getMaxTokens());
        assertEquals(Boolean.FALSE, req.getStream());
        assertEquals(2, req.getMessages().size());
        assertEquals("system", req.getMessages().get(0).getRole());
        assertEquals("You are a helpful assistant.", req.getMessages().get(0).getContent());
        assertEquals("user", req.getMessages().get(1).getRole());
        assertEquals("Hello", req.getMessages().get(1).getContent());
    }

    @Test
    public void parse_multimodal_image_url() throws Exception {
        String body = "{\n" +
                "  \"model\": \"openai/gpt-4o\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\":\"user\",\"content\":[\n" +
                "      {\"type\":\"text\",\"text\":\"describe this\"},\n" +
                "      {\"type\":\"image_url\",\"image_url\":{\"url\":\"https://x.com/a.png\",\"detail\":\"high\"}}\n" +
                "    ]}\n" +
                "  ]\n" +
                "}";
        JsonNode root = json.readTree(body);
        UnifiedRequest req = mapper.parse(root);

        List<UnifiedMessage> msgs = req.getMessages();
        assertEquals(1, msgs.size());
        UnifiedMessage m = msgs.get(0);
        assertEquals("user", m.getRole());
        assertNull(m.getContent());
        assertEquals(2, m.getContents().size());
        ContentPart p0 = m.getContents().get(0);
        assertEquals("text", p0.getType());
        assertEquals("describe this", p0.getText());
        ContentPart p1 = m.getContents().get(1);
        assertEquals("image_url", p1.getType());
        assertNotNull(p1.getImageUrl());
        assertEquals("https://x.com/a.png", p1.getImageUrl().getUrl());
        assertEquals("high", p1.getImageUrl().getDetail());
    }

    @Test
    public void parse_tools_and_tool_calls() throws Exception {
        String body = "{\n" +
                "  \"model\": \"openai/gpt-4o\",\n" +
                "  \"messages\": [\n" +
                "    {\"role\":\"assistant\",\"tool_calls\":[\n" +
                "      {\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Beijing\\\"}\"}}\n" +
                "    ]},\n" +
                "    {\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"{\\\"temp\\\":22}\"}\n" +
                "  ],\n" +
                "  \"tools\": [\n" +
                "    {\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"description\":\"get weather\",\"parameters\":{\"type\":\"object\"}}}\n" +
                "  ],\n" +
                "  \"tool_choice\": \"auto\"\n" +
                "}";
        JsonNode root = json.readTree(body);
        UnifiedRequest req = mapper.parse(root);

        List<ToolSpec> tools = req.getTools();
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).getType());
        assertEquals("get_weather", tools.get(0).getFunction().getName());
        assertEquals("get weather", tools.get(0).getFunction().getDescription());

        assertEquals("auto", req.getToolChoice());

        UnifiedMessage asst = req.getMessages().get(0);
        assertEquals("assistant", asst.getRole());
        assertEquals(1, asst.getToolCalls().size());
        assertEquals("call_1", asst.getToolCalls().get(0).getId());
        assertEquals("function", asst.getToolCalls().get(0).getType());
        assertEquals("get_weather", asst.getToolCalls().get(0).getFunction().getName());

        UnifiedMessage tool = req.getMessages().get(1);
        assertEquals("tool", tool.getRole());
        assertEquals("call_1", tool.getToolCallId());
        assertTrue(tool.getContent().contains("temp"));
    }

    @Test
    public void parse_stop_array() throws Exception {
        String body = "{\n" +
                "  \"model\": \"openai/gpt-4o\",\n" +
                "  \"messages\": [{\"role\":\"user\",\"content\":\"hi\"}],\n" +
                "  \"stop\": [\"END\", \"STOP\"]\n" +
                "}";
        UnifiedRequest req = mapper.parse(json.readTree(body));
        assertNotNull(req.getStop());
        assertEquals(2, req.getStop().size());
        assertEquals("END", req.getStop().get(0));
        assertEquals("STOP", req.getStop().get(1));
    }

    @Test
    public void parse_stop_string() throws Exception {
        String body = "{\n" +
                "  \"model\": \"openai/gpt-4o\",\n" +
                "  \"messages\": [{\"role\":\"user\",\"content\":\"hi\"}],\n" +
                "  \"stop\": \"END\"\n" +
                "}";
        UnifiedRequest req = mapper.parse(json.readTree(body));
        assertEquals(1, req.getStop().size());
        assertEquals("END", req.getStop().get(0));
    }
}