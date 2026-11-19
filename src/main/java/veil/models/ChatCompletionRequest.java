package veil.models;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible /v1/chat/completions request payload.
 * Preserves supported parameters and additional extension fields.
 */
public class ChatCompletionRequest {
    private String model;
    private List<ChatMessage> messages = new ArrayList<>();
    private Double temperature;
    @JsonProperty("top_p")
    private Double topP;
    @JsonProperty("max_tokens")
    private Integer maxTokens;
    private Boolean stream;
    private Object stop;
    @JsonProperty("presence_penalty")
    private Double presencePenalty;
    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;
    private String user;
    private final Map<String, Object> extraFields = new LinkedHashMap<>();

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<ChatMessage> messages) {
        this.messages = messages == null ? new ArrayList<>() : messages;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public boolean isStreaming() {
        return Boolean.TRUE.equals(stream);
    }

    public Object getStop() {
        return stop;
    }

    public void setStop(Object stop) {
        this.stop = stop;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    @JsonAnyGetter
    public Map<String, Object> getExtraFields() {
        return extraFields;
    }

    @JsonAnySetter
    public void putExtraField(String key, Object value) {
        extraFields.put(key, value);
    }

    public ChatCompletionRequest copyWithMessages(List<ChatMessage> newMessages) {
        ChatCompletionRequest copy = new ChatCompletionRequest();
        copy.setModel(this.model);
        copy.setMessages(newMessages);
        copy.setTemperature(this.temperature);
        copy.setTopP(this.topP);
        copy.setMaxTokens(this.maxTokens);
        copy.setStream(this.stream);
        copy.setStop(this.stop);
        copy.setPresencePenalty(this.presencePenalty);
        copy.setFrequencyPenalty(this.frequencyPenalty);
        copy.setUser(this.user);
        copy.extraFields.putAll(this.extraFields);
        return copy;
    }
}
