package chatweb.model;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

/**
 * ChatMessage — đối tượng tin nhắn truyền giữa server và clients.
 * Serialize thủ công sang JSON (không dùng thư viện ngoài).
 */
public class ChatMessage {

    public enum Type {
        GLOBAL,   // tin nhắn phòng chung
        DM,       // tin nhắn riêng
        SYSTEM,   // thông báo hệ thống (join/leave)
        USER_LIST,// cập nhật danh sách user
        WELCOME,  // chào mừng + history
        ERROR,    // lỗi
        TYPING,   // đang nhập
        TYPING_STOP,
        DM_HISTORY// lịch sử DM
    }

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private Type   type;
    private String sender;
    private String to;        // null nếu GLOBAL/SYSTEM
    private String content;
    private String timestamp;
    private String id;

    // ── Constructors ──────────────────────────────────────────────

    public ChatMessage() {}

    private ChatMessage(Type type, String sender, String to,
                        String content, String id) {
        this.type      = type;
        this.sender    = sender;
        this.to        = to;
        this.content   = content;
        this.timestamp = OffsetDateTime.now(ZoneOffset.ofHours(7)).format(FMT);
        this.id        = id != null ? id : generateId();
    }

    // ── Factory methods ───────────────────────────────────────────

    public static ChatMessage global(String sender, String content) {
        return new ChatMessage(Type.GLOBAL, sender, null, content, null);
    }

    public static ChatMessage dm(String sender, String to, String content) {
        return new ChatMessage(Type.DM, sender, to, content, null);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Type.SYSTEM, "SYSTEM", null, content, null);
    }

    public static ChatMessage error(String content) {
        return new ChatMessage(Type.ERROR, "SERVER", null, content, null);
    }

    public static ChatMessage typing(String sender, String room) {
        ChatMessage m = new ChatMessage(Type.TYPING, sender, room, "", null);
        if (m.timestamp == null || m.timestamp.isBlank()) {
            m.timestamp = OffsetDateTime.now(ZoneOffset.ofHours(7)).format(FMT);
        }
        return m;
    }

    public static ChatMessage typingStop(String sender, String room) {
        return new ChatMessage(Type.TYPING_STOP, sender, room, "", null);
    }

    // ── JSON serialization (thủ công, không dùng thư viện) ────────

    /**
     * Serialize sang JSON string để gửi qua WebSocket frame.
     */
    public String toJson() {
        return String.format(
                "{\"type\":\"%s\",\"sender\":\"%s\",\"to\":%s," +
                        "\"content\":\"%s\",\"timestamp\":\"%s\",\"id\":\"%s\"}",
                type,
                escapeJson(sender != null ? sender : ""),
                to != null ? "\"" + escapeJson(to) + "\"" : "null",
                escapeJson(content != null ? content : ""),
                timestamp != null ? timestamp : "",
                id != null ? id : ""
        );
    }

    /**
     * Parse JSON string thành ChatMessage.
     * Dùng regex đơn giản — đủ dùng cho protocol nội bộ.
     */
    public static ChatMessage fromJson(String json) {
        ChatMessage m = new ChatMessage();
        m.type      = Type.valueOf(extractStr(json, "type"));
        m.sender    = extractStr(json, "sender");
        m.to        = extractStr(json, "to");
        m.content   = extractStr(json, "content");
        m.timestamp = extractStr(json, "timestamp");
        m.id        = extractStr(json, "id");
        return m;
    }

    private static String extractStr(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return null;
        int start = colon + 1;
        // skip whitespace
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            // string value
            int end = start + 1;
            while (end < json.length()) {
                if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
                end++;
            }
            return unescapeJson(json.substring(start + 1, end));
        } else if (json.startsWith("null", start)) {
            return null;
        }
        return null;
    }

    // ── JSON Helpers ──────────────────────────────────────────────

    public static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public static String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }

    private static String generateId() {
        return Long.toHexString(System.nanoTime());
    }

    // ── Getters / Setters ─────────────────────────────────────────

    public Type   getType()      { return type; }
    public String getSender()    { return sender; }
    public String getTo()        { return to; }
    public String getContent()   { return content; }
    public String getTimestamp() { return timestamp; }
    public String getId()        { return id; }

    public void setType(Type t)        { this.type = t; }
    public void setSender(String s)    { this.sender = s; }
    public void setTo(String t)        { this.to = t; }
    public void setContent(String c)   { this.content = c; }
    public void setTimestamp(String t) { this.timestamp = t; }
    public void setId(String i)        { this.id = i; }

    @Override
    public String toString() {
        return "[" + type + "] " + sender + " → " + (to != null ? to : "all") + ": " + content;
    }
}