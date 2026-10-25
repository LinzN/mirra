/*
 * Copyright (c) 2026 MirraNET, Niklas Linz. All rights reserved.
 *
 * This file is part of the MirraNET project and is licensed under the
 * GNU Lesser General Public License v3.0 (LGPLv3).
 *
 * You may use, distribute and modify this code under the terms
 * of the LGPLv3 license. You should have received a copy of the
 * license along with this file. If not, see <https://www.gnu.org/licenses/lgpl-3.0.html>
 * or contact: niklas.linz@mirranet.de
 */

package de.linzn.mirra.openai;

import com.azure.ai.openai.models.*;

import java.util.ArrayList;
import java.util.List;

public class ChatMessage {
    private ChatRequestMessage chatRequestMessage = null;


    public ChatMessage(ChatRequestMessage chatRequestMessage) {
        this.chatRequestMessage = chatRequestMessage;
    }

    public ChatMessage(String content, ChatRole role) {
        if (role == ChatRole.ASSISTANT) {
            chatRequestMessage = new ChatRequestAssistantMessage(content);
        } else if (role == ChatRole.USER) {
            chatRequestMessage = new ChatRequestUserMessage(content);
        } else if (role == ChatRole.SYSTEM) {
            chatRequestMessage = new ChatRequestSystemMessage(content);
        } else if (role == ChatRole.DEVELOPER) {
            chatRequestMessage = new ChatRequestDeveloperMessage(content);
        } else {
            chatRequestMessage = new ChatRequestSystemMessage(content);
        }
    }

    public static ChatMessage buildsFrom(ChatResponseMessage chatResponseMessage) {
        ChatMessage chatMessage = null;
        if (chatResponseMessage.getRole() == ChatRole.ASSISTANT) {
            ChatRequestAssistantMessage chatRequestAssistantMessage = new ChatRequestAssistantMessage(chatResponseMessage.getContent());
            chatRequestAssistantMessage.setFunctionCall(chatResponseMessage.getFunctionCall());
            chatMessage = new ChatMessage(chatRequestAssistantMessage);
        }
        return chatMessage;
    }

    public static List<ChatRequestMessage> convertToRequestMessage(List<ChatMessage> messages) {
        List<ChatRequestMessage> converted = new ArrayList<>();
        for (ChatMessage chatMessage : messages) {
            converted.add(chatMessage.convertToRequestMessage());
        }
        return converted;
    }

    public ChatRole getRole() {
        return chatRequestMessage.getRole();
    }

    public String getContent() {
        String content;
        if (this.hasFunctionCall()) {
            content = null;
        } else {
            if (chatRequestMessage.getRole() == ChatRole.ASSISTANT) {
                content = asString(((ChatRequestAssistantMessage) chatRequestMessage).getContent());
            } else if (chatRequestMessage.getRole() == ChatRole.USER) {
                content = asString(((ChatRequestUserMessage) chatRequestMessage).getContent());
            } else if (chatRequestMessage.getRole() == ChatRole.SYSTEM) {
                content = asString(((ChatRequestSystemMessage) chatRequestMessage).getContent());
            } else if (chatRequestMessage.getRole() == ChatRole.DEVELOPER) {
                content = asString(((ChatRequestDeveloperMessage) chatRequestMessage).getContent());
            } else if (chatRequestMessage.getRole() == ChatRole.TOOL) {
                content = asString(((ChatRequestToolMessage) chatRequestMessage).getContent());
            } else if (chatRequestMessage.getRole() == ChatRole.FUNCTION) {
                content = ((ChatRequestFunctionMessage) chatRequestMessage).getContent();
            } else {
                throw new ChatMessageNoContentException();
            }
        }
        return content;
    }

    /* the SDK returns null content for assistant messages without text */
    private static String asString(Object content) {
        return content == null ? null : content.toString();
    }

    public ChatRequestMessage convertToRequestMessage() {
        return chatRequestMessage;
    }

    public boolean hasFunctionCall() {
        if (this.chatRequestMessage instanceof ChatRequestAssistantMessage) {
            return ((ChatRequestAssistantMessage) chatRequestMessage).getFunctionCall() != null;
        }
        return false;
    }

    public FunctionCall getFunctionCall() {
        if (this.chatRequestMessage instanceof ChatRequestAssistantMessage) {
            return ((ChatRequestAssistantMessage) chatRequestMessage).getFunctionCall();
        }
        return null;
    }
}
