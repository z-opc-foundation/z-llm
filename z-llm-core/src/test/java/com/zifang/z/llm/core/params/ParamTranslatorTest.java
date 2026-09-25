package com.zifang.z.llm.core.params;

import com.zifang.z.llm.api.dto.UnifiedRequest;
import com.zifang.z.llm.api.dto.Vendor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 采样参数按 vendor 协议下沉 — 这些字段此前被解析进 UnifiedRequest 后又整体丢掉.
 */
public class ParamTranslatorTest {

    private UnifiedRequest full() {
        UnifiedRequest r = new UnifiedRequest();
        r.setModel("openai/gpt-4o");
        r.setStop(Arrays.asList("END", "STOP"));
        r.setToolChoice("required");
        r.setPresencePenalty(0.5);
        r.setFrequencyPenalty(1.25);
        r.setUser("u-1");
        return r;
    }

    @Test
    public void openAiForwardsAllStandardKnobs() {
        Map<String, Object> p = ParamTranslator.forVendor(Vendor.OPENAI, full());
        assertEquals(Arrays.asList("END", "STOP"), p.get("stop"));
        assertEquals("required", p.get("tool_choice"));
        assertEquals(0.5, p.get("presence_penalty"));
        assertEquals(1.25, p.get("frequency_penalty"));
        assertEquals("u-1", p.get("user"));
    }

    /** Anthropic 用 stop_sequences, 且没有 presence/frequency penalty — 多发一个字段就是上游 400. */
    @Test
    public void anthropicRenamesStopAndDropsUnsupported() {
        Map<String, Object> p = ParamTranslator.forVendor(Vendor.ANTHROPIC, full());
        assertEquals(Arrays.asList("END", "STOP"), p.get("stop_sequences"));
        assertFalse("must not send OpenAI-only field", p.containsKey("stop"));
        assertFalse(p.containsKey("presence_penalty"));
        assertFalse(p.containsKey("frequency_penalty"));
        assertFalse(p.containsKey("user"));
        assertEquals("required", p.get("tool_choice"));
    }

    @Test
    public void geminiUsesCamelCaseStopSequences() {
        Map<String, Object> p = ParamTranslator.forVendor(Vendor.GEMINI, full());
        assertEquals(Arrays.asList("END", "STOP"), p.get("stopSequences"));
        assertFalse(p.containsKey("stop"));
    }

    @Test
    public void emptyOrNullFieldsAreNotSent() {
        UnifiedRequest r = new UnifiedRequest();
        r.setStop(Collections.<String>emptyList());
        Map<String, Object> p = ParamTranslator.forVendor(Vendor.OPENAI, r);
        assertTrue(p.isEmpty());
    }

    /** 任意扩展字段只对 OpenAI 兼容协议放开. */
    @Test
    public void extraOnlyReachesOpenAiCompatibleVendors() {
        UnifiedRequest r = full();
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("seed", 42);
        r.setExtra(extra);
        assertEquals(42, ParamTranslator.forVendor(Vendor.DEEPSEEK, r).get("seed"));
        assertEquals(42, ParamTranslator.forVendor(Vendor.OPENAI, r).get("seed"));
        assertNull("anthropic must not receive arbitrary extra",
                ParamTranslator.forVendor(Vendor.ANTHROPIC, r).get("seed"));
        assertNull(ParamTranslator.forVendor(Vendor.GEMINI, r).get("seed"));
    }

    @Test
    public void nullRequestIsSafe() {
        assertTrue(ParamTranslator.forVendor(Vendor.OPENAI, null).isEmpty());
    }
}
