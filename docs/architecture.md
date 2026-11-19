# VEIL System Architecture

VEIL is structured into four cleanly separated components. The core privacy engine (`veil.core`, `veil.detection`, `veil.tokenization`, `veil.vault`, `veil.policies`, `veil.profiles`, `veil.streaming`, `veil.providers`, `veil.analytics`, `veil.sdk`) has **zero dependency on JavaFX**.

```
USER APPLICATIONS (IDEs, CLI Assistants, Custom Java Apps)
                          |
                          v
          [Component B] LocalApiGateway (127.0.0.1:8080)
          GatewaySecurityFilter (Bearer Auth, Rate Limit, Size Guard)
                          |
                          v
          [Component A] VeilCoreEngine (JavaFX-Free Facade)
             1. DetectionEngine (Secret, Regex, Custom, CodeId, Name)
             2. PolicyEngine (MASK / BLOCK / WARN / REALISTIC_FAKE / ALLOW)
             3. TokenVault (Session-isolated bidirectional map, AES-256-GCM)
             4. ProviderRegistry -> AIProviderAdapter (OpenAI/Anthropic/Gemini/Ollama)
             5. TokenRestorer / StreamingTokenRestorer (Incremental split-chunk buffer)
             6. AnalyticsService (SQLite metadata-only telemetry)
                          |
                          v
          [Component C] JavaFX Control Centre (VeilShell + 9 Views + TrayManager)
          [Component D] Developer SDK (VeilClient) & Extension REST Contracts
```

## Design Patterns Used

- **Strategy**: `PolicyStrategy` (`MaskPolicyStrategy`, `RealisticFakePolicyStrategy`) and `Detector` implementations.
- **Adapter**: `AIProviderAdapter` (`OpenAIAdapter`, `AnthropicAdapter`, `GeminiAdapter`, `OllamaAdapter`) normalizing heterogeneous provider wire protocols.
- **Factory / Registry**: `ProviderRegistry` selecting adapters by `ProviderType`.
- **Facade / Proxy**: `VeilCoreEngine` and `LocalApiGateway` intercepting and transforming requests transparently.
- **Observer**: `PrivacyEventListener` pushing genuine request and gateway state changes to the JavaFX UI and system tray.
