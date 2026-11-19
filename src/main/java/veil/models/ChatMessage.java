package veil.models;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI-compatible chat message model preserving unknown fields if needed.
 */
public class ChatMessage {
    private String role;
    private String content;
    private String name;

    public ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", content);
        if (name != null) {
            map.put("name", name);
        }
        return map;
    }
}
