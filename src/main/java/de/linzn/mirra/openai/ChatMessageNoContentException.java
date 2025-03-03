package de.linzn.mirra.openai;

public class ChatMessageNoContentException extends IllegalArgumentException {
    public ChatMessageNoContentException() {
        super("ChatMessage has no content!");
    }
}
