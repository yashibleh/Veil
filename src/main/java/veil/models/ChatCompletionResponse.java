package veil.models;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * OpenAI-compatible /v1/chat/completions response payload.
 */
public class ChatCompletionResponse {
    private String id;
    private String object = "chat.completion";
    private long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    public ChatCompletionResponse() {
        this.id = "chatcmpl-veil-" + UUID.randomUUID().toString().substring(0, 12);
        this.created = Instant.now().getEpochSecond();
    }

    public static ChatCompletionResponse simple(String model, String content, int promptTokens, int completionTokens) {
        ChatCompletionResponse resp = new ChatCompletionResponse();
        resp.setModel(model);
        Choice c = new Choice();
        c.setIndex(0);
        c.setMessage(new ChatMessage("assistant", content));
        c.setFinishReason("stop");
        resp.setChoices(List.of(c));
        Usage u = new Usage();
        u.setPromptTokens(promptTokens);
        u.setCompletionTokens(completionTokens);
        u.setTotalTokens(promptTokens + completionTokens);
        resp.setUsage(u);
        return resp;
    }

    public String getFirstAssistantContent() {
        if (choices == null || choices.isEmpty() || choices.get(0).getMessage() == null) {
            return "";
        }
        String c = choices.get(0).getMessage().getContent();
        return c == null ? "" : c;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getObject() {
        return object;
    }

    public void setObject(String object) {
        this.object = object;
    }

    public long getCreated() {
        return created;
    }

    public void setCreated(long created) {
        this.created = created;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Choice> getChoices() {
        return choices;
    }

    public void setChoices(List<Choice> choices) {
        this.choices = choices;
    }

    public Usage getUsage() {
        return usage;
    }

    public void setUsage(Usage usage) {
        this.usage = usage;
    }

    public static class Choice {
        private int index;
        private ChatMessage message;
        @JsonProperty("finish_reason")
        private String finishReason;

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public ChatMessage getMessage() {
            return message;
        }

        public void setMessage(ChatMessage message) {
            this.message = message;
        }

        public String getFinishReason() {
            return finishReason;
        }

        public void setFinishReason(String finishReason) {
            this.finishReason = finishReason;
        }
    }

    public static class Usage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("completion_tokens")
        private int completionTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;

        public int getPromptTokens() {
            return promptTokens;
        }

        public void setPromptTokens(int promptTokens) {
            this.promptTokens = promptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public void setCompletionTokens(int completionTokens) {
            this.completionTokens = completionTokens;
        }

        public int getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(int totalTokens) {
            this.totalTokens = totalTokens;
        }
    }
}
