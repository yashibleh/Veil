package veil.streaming;

import veil.vault.SessionVault;
import veil.vault.TokenMapping;
import veil.vault.TokenVault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Incremental streaming token restorer that handles tokens split across arbitrary SSE chunks
 * (e.g., Chunk 1: "[EMAIL_", Chunk 2: "001]") without buffering the entire response.
 *
 * Only retains the minimal tail suffix that could be a valid prefix of a known token
 * in the current session vault. Malformed or unrelated bracket sequences are flushed safely.
 */
public class StreamingTokenRestorer {

    private final List<TokenMapping> sessionMappings;
    private final int maxTokenLength;
    private final StringBuilder pendingBuffer = new StringBuilder();

    public StreamingTokenRestorer(String sessionId, TokenVault vault) {
        Optional<SessionVault> opt = (vault == null || sessionId == null)
                ? Optional.empty()
                : vault.getSession(sessionId);

        if (opt.isPresent()) {
            this.sessionMappings = opt.get().getMappings().stream()
                    .sorted(Comparator.comparingInt((TokenMapping m) -> m.getToken().length()).reversed())
                    .toList();
        } else {
            this.sessionMappings = new ArrayList<>();
        }
        this.maxTokenLength = this.sessionMappings.stream()
                .mapToInt(m -> m.getToken().length())
                .max()
                .orElse(0);
    }

    /**
     * Accepts the next incoming stream chunk and returns the portion safe to emit immediately.
     */
    public String processChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        if (sessionMappings.isEmpty()) {
            return chunk;
        }

        pendingBuffer.append(chunk);
        String working = pendingBuffer.toString();
        StringBuilder output = new StringBuilder();

        int i = 0;
        while (i < working.length()) {
            // 1. Check for exact token match at position i
            TokenMapping matched = findExactMatchAt(working, i);
            if (matched != null) {
                output.append(matched.getOriginalValue());
                i += matched.getToken().length();
                continue;
            }

            // 2. Check if the remaining tail working[i..] is a strict prefix of any known session token
            String remaining = working.substring(i);
            if (remaining.length() < maxTokenLength && isPrefixOfAnyToken(remaining)) {
                break; // hold remaining tail in buffer until next chunk or flush()
            }

            // 3. Safe to emit character i immediately
            output.append(working.charAt(i));
            i++;
        }

        pendingBuffer.setLength(0);
        if (i < working.length()) {
            pendingBuffer.append(working, i, working.length());
        }
        return output.toString();
    }

    /**
     * Flushes any held tail when the stream completes or is interrupted, ensuring no text is lost.
     */
    public String flush() {
        if (pendingBuffer.isEmpty()) {
            return "";
        }
        String tail = pendingBuffer.toString();
        pendingBuffer.setLength(0);
        // Perform final exact replacement pass in case the tail completed a token
        for (TokenMapping mapping : sessionMappings) {
            if (tail.contains(mapping.getToken())) {
                tail = tail.replace(mapping.getToken(), mapping.getOriginalValue());
            }
        }
        return tail;
    }

    private TokenMapping findExactMatchAt(String text, int offset) {
        for (TokenMapping mapping : sessionMappings) {
            String tok = mapping.getToken();
            if (text.startsWith(tok, offset)) {
                return mapping;
            }
        }
        return null;
    }

    private boolean isPrefixOfAnyToken(String candidatePrefix) {
        for (TokenMapping mapping : sessionMappings) {
            String tok = mapping.getToken();
            if (tok.length() > candidatePrefix.length() && tok.startsWith(candidatePrefix)) {
                return true;
            }
        }
        return false;
    }
}
