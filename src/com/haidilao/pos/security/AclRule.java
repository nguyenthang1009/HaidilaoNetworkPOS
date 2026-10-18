package com.haidilao.pos.security;

/**
 * Định nghĩa một luật trong danh sách kiểm soát truy cập (Access Control List - ACL)
 */
public class AclRule {
    private int ruleNumber;
    private String action; // PERMIT / DENY
    private String protocol; // TCP / IP
    private String source;
    private String destination;
    private int port;
    private String description;

    public AclRule(int ruleNumber, String action, String protocol, String source, String destination, int port, String description) {
        this.ruleNumber = ruleNumber;
        this.action = action;
        this.protocol = protocol;
        this.source = source;
        this.destination = destination;
        this.port = port;
        this.description = description;
    }

    public int getRuleNumber() {
        return ruleNumber;
    }

    public String getAction() {
        return action;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getSource() {
        return source;
    }

    public String getDestination() {
        return destination;
    }

    public int getPort() {
        return port;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return String.format("[ACL Rule %d] %s %s %s %s eq %d (%s)",
                ruleNumber, action, protocol, source, destination, port, description);
    }
}
