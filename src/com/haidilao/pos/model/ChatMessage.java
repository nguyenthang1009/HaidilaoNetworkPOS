package com.haidilao.pos.model;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;

/**
 * Model nhật ký tin nhắn và tín hiệu chuông
 */
public class ChatMessage {
    private int logId;
    private String sender;
    private String receiver;
    private String messageType; // CHAT, RING, ORDER, SECURITY
    private String content;
    private Timestamp sentAt;

    public ChatMessage() {
    }

    public ChatMessage(int logId, String sender, String receiver, String messageType, String content, Timestamp sentAt) {
        this.logId = logId;
        this.sender = sender;
        this.receiver = receiver;
        this.messageType = messageType;
        this.content = content;
        this.sentAt = sentAt;
    }

    public int getLogId() {
        return logId;
    }

    public void setLogId(int logId) {
        this.logId = logId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Timestamp getSentAt() {
        return sentAt;
    }

    public void setSentAt(Timestamp sentAt) {
        this.sentAt = sentAt;
    }

    public String getFormattedTime() {
        if (sentAt == null) return "";
        return new SimpleDateFormat("HH:mm:ss").format(sentAt);
    }
}
