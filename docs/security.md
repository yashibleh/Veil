# VEIL Security Model & Privacy Limitations

## Security Controls Implemented

1. **Encrypted Token Vault (`VaultCrypto`)**:
   - Cipher: **AES-256-GCM** (authenticated encryption with 128-bit GCM tag).
   - Key Derivation: **PBKDF2WithHmacSHA256** (120,000 iterations) with a fresh 16-byte `SecureRandom` salt and 12-byte nonce on every write.
   - Locking clears in-memory mappings and overwrites the passphrase buffer.
2. **Protected Provider Credentials (`CredentialStore`)**:
   - API keys are never hardcoded, never logged, and never displayed in full in the UI.
   - Stored encrypted at `~/.veil/credentials.enc` with owner-only POSIX permissions (NTFS user-profile ACLs on Windows), or supplied via `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`.
3. **Local Gateway Access Controls (`GatewaySecurityFilter`)**:
   - Binds strictly to `127.0.0.1` by default (never exposed on `0.0.0.0`).
   - Constant-time (`MessageDigest.isEqual`) Bearer token verification enabled by default.
   - Request payload cap (default 2 MB) and sliding-window rate limiting (default 120 req/min).
4. **Metadata-Only Telemetry (`AnalyticsService`)**:
   - SQLite records timestamps, provider, model, status, entity counts, and latency — never raw prompt text or raw secrets.
5. **Contextual Restoration**:
   - `TokenRestorer` and `StreamingTokenRestorer` only restore exact tokens present in the active session's vault; unrelated or hallucinated `[EMAIL_999]` strings are never replaced.

## Honest Privacy Limitations

- **Scope**: Only traffic routed through VEIL's local gateway or SDK is protected.
- **Imperfect Detection**: Regex, checksum, entropy, and contextual heuristics can miss obfuscated or domain-specific secrets (false negatives) or occasionally flag benign strings (false positives).
- **Provider Retention**: Sanitized prompts forwarded to cloud providers remain subject to those providers' retention policies.
- **Vault Sensitivity**: Reversible token mappings are themselves sensitive; lock or wipe sessions after handling confidential data.
- **Evaluation Benchmark Caveat**: The 200-prompt evaluation dataset in `src/test/resources/evaluation/evaluation_prompts_200.json` measures regression quality on curated examples, not real-world guarantees.

## Control surface (web UI) hardening

The interface is served by `veil.web.ControlServer`, bound to `127.0.0.1` only (default port 8787, next free port up to 8797).

- **Per-launch UI token**: the launcher opens the window at `/?t=<random>`; the server swaps it for an `HttpOnly; SameSite=Strict` cookie. Without the cookie every route returns 401. The token is visible in the launcher process's command line to other local processes of the same user, the same trust boundary as `~/.veil`.
- **DNS-rebinding guard**: requests whose `Host` is not `127.0.0.1:<port>` / `localhost:<port>` are refused.
- **CSRF**: every mutating call needs the `X-Veil-UI: 1` header and a same-origin `Origin`.
- **CSP**: `default-src 'self'`, no inline scripts, no remote fonts, images or scripts. The UI makes no third-party requests.
- **Secrets**: API keys are write-only (only a masked form is returned). Vault originals are returned only when the Depths lens is switched on, and are dropped client-side when the room closes. Prompt text typed in the Atelier is sent only to this loopback server, and to a provider only if you press **Send**.
- **WARN policy**: requests that hit a WARN rule pause until you answer in the UI (90 s timeout, then refuse). With no UI connected they are refused (fail-safe).

## Conversations (Salon)

- Conversations are held **in memory only**; nothing is written to disk and they end with the process. Each one owns a single vault session, deleted with the conversation.
- Each message is stored twice: as you wrote it (shown to you) and in "wire" form (what the provider received). History is always re-sent in wire form, so original values are never sent to the provider on later turns, and only the new message is scanned, so WARN prompts are not repeated.
- Replies are restored token by token while streaming, including tokens split across network chunks.
- Conversation turns appear in Rain with the source `SALON`, like any other traffic.
