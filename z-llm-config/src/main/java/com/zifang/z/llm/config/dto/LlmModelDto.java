package com.zifang.z.llm.config.dto;

public class LlmModelDto {
    private Long id;
    private String modelCode;
    private String modelName;
    private String providerCode;
    private String modelType;
    private Integer contextWindow;
    private Integer supportsFunctionCall;
    private Integer supportsVision;
    private Integer maxOutputTokens;
    private Double inputPrice;
    private Double outputPrice;
    private Integer enabled;
    private String description;
    private String defaultParams;
    private String extConfig;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getModelCode() {
        return modelCode;
    }

    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
    }

    public String getModelType() {
        return modelType;
    }

    public void setModelType(String modelType) {
        this.modelType = modelType;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public Integer getSupportsFunctionCall() {
        return supportsFunctionCall;
    }

    public void setSupportsFunctionCall(Integer supportsFunctionCall) {
        this.supportsFunctionCall = supportsFunctionCall;
    }

    public Integer getSupportsVision() {
        return supportsVision;
    }

    public void setSupportsVision(Integer supportsVision) {
        this.supportsVision = supportsVision;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Double getInputPrice() {
        return inputPrice;
    }

    public void setInputPrice(Double inputPrice) {
        this.inputPrice = inputPrice;
    }

    public Double getOutputPrice() {
        return outputPrice;
    }

    public void setOutputPrice(Double outputPrice) {
        this.outputPrice = outputPrice;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultParams() {
        return defaultParams;
    }

    public void setDefaultParams(String defaultParams) {
        this.defaultParams = defaultParams;
    }

    public String getExtConfig() {
        return extConfig;
    }

    public void setExtConfig(String extConfig) {
        this.extConfig = extConfig;
    }
}