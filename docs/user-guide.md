# VEIL User Guide

## First-Time Workflow (3 Minutes)

1. **Launch VEIL**: Run `run-veil.bat` / `./run-veil.sh` (or `java -jar target/veil-privacy-gateway-1.0.0.jar`). The glass interface opens in an app-mode Edge/Chrome window.
2. **Configure a Provider (**Routes**, key `2`)**:
   - Pick **OpenAI**, **Anthropic**, **Google Gemini**, or local **Ollama**.
   - Enter your model and API key (if cloud), click **Save & Set Active Provider**, then click **Test Connection**.
3. **Choose a Privacy Profile (`04 Privacy Profiles`)**:
   - Built-in profiles: **Personal**, **Developer** (default), **Enterprise**, **Strict**.
   - Customize per-category policies (`MASK`, `BLOCK`, `WARN`, `REALISTIC_FAKE`, `ALLOW`), add internal code identifiers, or create custom rules.
4. **Start the Local Gateway (**Breath**, key `1`)**:
   - Click **Start Gateway** and copy the endpoint (`http://127.0.0.1:8080/v1/chat/completions`) and Local Access Token.
5. **Connect Your AI Tool**:
   - Configure your IDE assistant or CLI tool to use VEIL's Base URL (`http://127.0.0.1:8080/v1`) and Local Access Token.
   - Minimize VEIL to the system tray and continue working normally.

## Managing the Token Vault (`06 Token Vault`)

- By default, mappings live **in-memory only** and expire after the configured session TTL (default 60 minutes).
- Original sensitive values are **hidden by default** in the table; check **Reveal original sensitive values** only when needed.
- To persist mappings across restarts, enter a passphrase (8+ characters) and click **Enable Encrypted Persistence**. Use **Lock Vault** or **Secure Wipe All** anytime.

## Testing & Debugging (**Atelier**, key `6`)

Use the Atelier to watch detections light up as you type, then test how your active profile detects, masks, or blocks sample prompts before routing production workflows.
