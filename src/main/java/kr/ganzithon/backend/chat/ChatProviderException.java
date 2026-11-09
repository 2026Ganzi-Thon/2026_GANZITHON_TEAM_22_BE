package kr.ganzithon.backend.chat;

/** Safe provider failure metadata; never contains prompts, credentials, or raw upstream bodies. */
public final class ChatProviderException extends RuntimeException {
    private final String code;
    private final boolean chargeableUnknown;
    private final ChatProvider.Usage usage;

    public ChatProviderException(String code, boolean chargeableUnknown, ChatProvider.Usage usage) {
        super(code);
        this.code = code;
        this.chargeableUnknown = chargeableUnknown;
        this.usage = usage;
    }

    public String code() { return code; }
    public boolean chargeableUnknown() { return chargeableUnknown; }
    public ChatProvider.Usage usage() { return usage; }
}
