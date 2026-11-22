# VEIL — Local-First Privacy Gateway for AI Interactions

**VEIL** is a local-first Java 17 desktop application and HTTP privacy gateway that sits between your AI-enabled tools and external Large Language Model (LLM) providers. Configure your IDE assistant, CLI tool, or custom Java app once to point at VEIL's local endpoint, and VEIL runs quietly in the background — detecting sensitive data, enforcing your privacy policies, applying reversible tokenization, forwarding sanitized requests, and restoring original values in both standard and streamed responses.

> **Core Philosophy:** Privacy should work in the background, not become another copy-paste task.

---

## Key Capabilities

- **Local OpenAI-Compatible API Gateway (`127.0.0.1:8080/v1/chat/completions`)**: Supports standard JSON and Server-Sent Events (SSE) streaming, Bearer token access control, rate limiting, and request size limits.
- **Modular Provider Adapter Layer**: Built-in adapters for **OpenAI**, **Anthropic**, **Google Gemini**, and local **Ollama** (clearly marked as local routing).
- **5-Stage Sensitive Information Detection Engine**: `SecretDetector` (Shannon entropy + placeholder filtering), `RegexDetector` (Verhoeff Aadhaar & Luhn payment card checksum validation, PAN, emails, phones, IPs, internal URLs, DB URIs), `CustomRuleDetector`, `CodeIdentifierDetector`, and `NameDetector` with deterministic overlap resolution.
- **Reversible Tokenization & Encrypted Vault**: Session-isolated bidirectional mappings (`[EMAIL_001]`, `[IP_001]`, or realistic synthetic values) with optional **AES-256-GCM + PBKDF2WithHmacSHA256** persistent storage, lock/unlock, TTL expiry, and secure wipe.
- **Privacy Policy Engine & Configurable Profiles**: Four enforcement strategies (`MASK`, `BLOCK`, `WARN`, `REALISTIC_FAKE`, plus `ALLOW`) bundled into editable built-in profiles (`Personal`, `Developer`, `Enterprise`, `Strict`) and custom profiles.
- **Fragmented Streaming Restoration**: `StreamingTokenRestorer` incrementally buffers only minimal token-prefix tails so tokens split across SSE chunks (`[EMAIL_` + `001]`) restore cleanly without full-response buffering.
- **Liquid-Glass Interface (WebGL2)**: no dashboard, no sidebar. A living glass orb that blooms into seven rooms (Breath, Routes, Masks, Rain, Depths, Atelier, Quiet). Every real request falls into the orb as a drop of glass; blocked ones bounce off. Runs in a chromeless Edge/Chrome app window served from a loopback-only control server, with a command bar (`/`) and system tray.
- **Developer SDK (`veil.sdk.VeilClient`)**: Embed VEIL directly in Java services or CLI tools.

---

## Prerequisites & Setup

1. **Java 17 or newer (JDK)** — e.g. Eclipse Temurin 17 or 21 LTS:
   - Windows: `winget install EclipseAdoptium.Temurin.21.JDK`
   - macOS: `brew install --cask temurin@21`
   - Linux (Debian/Ubuntu): `sudo apt install openjdk-21-jdk`
2. **Apache Maven 3.9+**:
   - Windows: `winget install Apache.Maven`
   - macOS: `brew install maven`
   - Linux: `sudo apt install maven`

---

## Build, Test & Run

```bash
cd VEIL

# Run the complete automated test suite (detection, vault, policy, streaming, local gateway, SDK, 200-prompt evaluation)
mvn clean test

# Build and launch VEIL (opens the glass interface in an app-mode Edge/Chrome window)
mvn -q -DskipTests package && java -jar target/veil-privacy-gateway-1.0.0.jar
# flags: --no-window (print the URL only), --autostart (wake the gateway on launch)
```

On Windows, you can also run `run-veil.bat`; on macOS/Linux, run `./run-veil.sh`.

---

## Quick Verification with cURL

1. Launch VEIL and wake the gateway in **Breath** (press `1`, or click the veil, then **Breath**) (default `http://127.0.0.1:8080/v1/chat/completions`).
2. Copy your local gateway access token from **Breath** and configure a provider (or local Ollama) in **Routes**.
3. Send a test request:

```bash
curl -X POST http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <YOUR_VEIL_GATEWAY_TOKEN>" \
  -d '{"messages":[{"role":"user","content":"Check host 192.168.1.12 and notify rahul@example.com"}]}'
```

---

## Previewing the interface without Java

The UI is plain ES modules (no build step, no CDN, no external fonts). To preview it with built-in demo data:

```
cd src/main/resources/web
python -m http.server 5173      # or: npx serve .
# open http://localhost:5173/?demo
```

Keys: `/` or `Ctrl+K` command bar, `1`-`7` rooms, `Esc` back. WebGL2 is required for the glass; without it a CSS-glass fallback is used.

## Documentation

- [Architecture Overview](docs/architecture.md)
- [Client & Provider Integrations](docs/integrations.md)
- [Security Model & Known Limitations](docs/security.md)
- [User Guide](docs/user-guide.md)

---

## Known Limitations

- VEIL **only protects requests actually routed through its local gateway or Java SDK**. It does not perform unsafe system-wide HTTPS interception.
- Detection is heuristic and rule-based; no detector guarantees 100% recall or 0% false positives across arbitrary text.
- External cloud AI providers process and retain sanitized requests under their own privacy policies (use Ollama for strictly local inference).
- Reversible token mappings are sensitive data; protect persistent vaults with a strong passphrase and lock or wipe sessions when finished.
- Browser extension integration is API-ready (`/v1/veil/sanitize`, `/v1/veil/restore`) but no browser extension package is bundled in this release.
