# VEIL Integrations Guide

## Supported OpenAI-Compatible API Contract

VEIL's local gateway implements:
- `GET /health` — Local status, active provider, active profile, and vault lock state (unauthenticated, no secrets).
- `GET /v1/models` — Lists the currently configured active model.
- `POST /v1/chat/completions` — Accepts `model`, `messages` (`role`, `content`, `name`), `temperature`, `top_p`, `max_tokens`, `stream`, `stop`, `presence_penalty`, `frequency_penalty`, `user`, and preserves additional JSON extension fields.
- Optional routing headers:
  - `Authorization: Bearer <VEIL_GATEWAY_TOKEN>` (or `X-Veil-Token`)
  - `X-Veil-Session: <session-id>` (reuses consistent tokens across multi-turn conversations)
  - `X-Veil-Provider: OPENAI|ANTHROPIC|GEMINI|OLLAMA` (per-request provider override)

Unsupported OpenAI endpoints (such as `/v1/embeddings`, `/v1/images`, or Assistants API) are intentionally not claimed as supported.

## Configuring AI Coding Assistants & CLI Tools

Point any client that supports a custom OpenAI Base URL to:
- **Base URL**: `http://127.0.0.1:8080/v1`
- **API Key**: Your local VEIL Gateway Token (copied from **Breath**)

VEIL authenticates your client locally, sanitizes the request, and attaches your real upstream provider credential (OpenAI, Anthropic, or Gemini) only when forwarding the sanitized payload.

## Local Routing with Ollama

Select **Ollama (Local)** in **Routes** (default `http://127.0.0.1:11434`, model `llama3.2`). VEIL clearly indicates `[LOCAL]` routing so you know prompts remain on your machine.

## Using the Java Developer SDK

```java
VeilClient client = new VeilClient(Path.of(System.getProperty("user.home"), ".veil"));
String session = client.createSession();
SanitizationResult result = client.sanitize(session, "Debug 192.168.1.12 for rahul@example.com");
if (!result.isBlocked()) {
    String restored = client.restore(session, "Checked [IP_001] and notified [EMAIL_001].");
}
client.clearSession(session);
```

## Future Browser & IDE Extension Contract

VEIL exposes authenticated local endpoints `POST /v1/veil/sanitize` and `POST /v1/veil/restore` so future browser extensions or IDE plugins can integrate without bypassing website security or sending raw sensitive values to third-party extension servers. No browser extension is bundled in v1.0.0.
