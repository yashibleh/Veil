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
