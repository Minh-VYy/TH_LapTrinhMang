package chatweb.server;

import chatweb.handler.ClientHandler;
import chatweb.model.ChatMessage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * ChatRoom — quản lý toàn bộ state của server:
 *   - Danh sách user online
 *   - Lịch sử tin nhắn global
 *   - Lịch sử DM từng cặp user
 *   - Broadcast / unicast messages
 *
 * Thread-safe: dùng ConcurrentHashMap + CopyOnWriteArrayList
 * vì nhiều ClientHandler thread cùng truy cập.
 */
public class ChatRoom {

    private static final Logger LOG = Logger.getLogger(ChatRoom.class.getName());

    private static final int MAX_GLOBAL_HISTORY = 200;
    private static final int MAX_DM_HISTORY     = 100;

    // username → ClientHandler
    private final ConcurrentHashMap<String, ClientHandler> users = new ConcurrentHashMap<>();

    // Global chat history
    private final List<ChatMessage> globalHistory = new CopyOnWriteArrayList<>();

    // DM history: "userA::userB" → list (tên được sort để luôn cùng key)
    private final ConcurrentHashMap<String, List<ChatMessage>> dmHistory = new ConcurrentHashMap<>();

    // ── User management ───────────────────────────────────────────

    /**
     * Đăng ký user mới. Dùng "name#id" làm key → không bao giờ trùng.
     */
    public synchronized void registerUser(String displayKey, ClientHandler handler) {
        users.put(displayKey, handler);
        LOG.info("[JOIN] " + displayKey + " | Online: " + users.size());
    }

    /**
     * Gọi khi user join thành công:
     *   1. Gửi WELCOME + history cho user mới
     *   2. Broadcast user:join cho mọi người
     *   3. Broadcast updated user list
     */
    public void onUserJoined(ClientHandler handler) {
        String displayKey = handler.getDisplayKey();

        // 1. Gửi WELCOME với lịch sử global
        handler.sendRaw(buildWelcomeJson(displayKey));

        // 2. Broadcast JOIN notification
        ChatMessage joinMsg = ChatMessage.system(handler.getUsername() + " đã tham gia phòng chat 👋");
        broadcastAll(joinMsg);

        // 3. Broadcast updated user list
        broadcastUserList();
    }

    /**
     * Gọi khi user disconnect.
     */
    public void onUserLeft(ClientHandler handler) {
        String displayKey = handler.getDisplayKey();
        users.remove(displayKey);
        LOG.info("[LEAVE] " + displayKey + " | Online: " + users.size());

        broadcastAll(ChatMessage.system(handler.getUsername() + " đã rời phòng chat 🚪"));
        broadcastUserList();
    }

    // ── Broadcast ─────────────────────────────────────────────────

    /**
     * Gửi tin nhắn global tới TẤT CẢ user online.
     */
    public void broadcastGlobal(ChatMessage msg) {
        addToHistory(globalHistory, msg, MAX_GLOBAL_HISTORY);
        broadcastAll(msg);
    }

    /**
     * Gửi tin nhắn tới tất cả (system messages, user list updates...).
     */
    public void broadcastAll(ChatMessage msg) {
        String json = msg.toJson();
        users.values().forEach(h -> h.sendRaw(json));
    }

    /**
     * Gửi DM giữa 2 user (chỉ tới sender + receiver).
     */
    public void sendDM(ChatMessage msg) {
        String from = msg.getSender();  // "Name#id"
        String to   = msg.getTo();      // "Name#id"

        String key = dmKey(from, to);
        dmHistory.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        addToHistory(dmHistory.get(key), msg, MAX_DM_HISTORY);

        ClientHandler receiver = users.get(to);
        if (receiver == null) {
            ClientHandler sender = users.get(from);
            // Extract display name from key for error message
            String toName = to.contains("#") ? to.substring(0, to.lastIndexOf('#')) : to;
            if (sender != null) sender.send(ChatMessage.error(toName + " không online."));
            return;
        }
        receiver.send(msg);

        ClientHandler sender = users.get(from);
        if (sender != null) sender.send(msg);
    }

    /**
     * Gửi lịch sử DM cho 1 user khi họ mở conversation.
     */
    public void sendDMHistory(ClientHandler handler, String other) {
        String key  = dmKey(handler.getUsername(), other);
        List<ChatMessage> hist = dmHistory.getOrDefault(key, Collections.emptyList());

        // Build JSON array
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"DM_HISTORY\",\"sender\":\"SERVER\",\"to\":\"")
                .append(ChatMessage.escapeJson(other))
                .append("\",\"content\":\"\",\"timestamp\":\"\",\"id\":\"\",\"messages\":[");

        for (int i = 0; i < hist.size(); i++) {
            sb.append(hist.get(i).toJson());
            if (i < hist.size() - 1) sb.append(',');
        }
        sb.append("]}");

        handler.sendRaw(sb.toString());
    }

    // ── Typing ────────────────────────────────────────────────────

    public void broadcastTyping(String sender, String target) {
        if (target == null) {
            // Typing in global → gửi cho tất cả trừ sender
            ChatMessage msg = ChatMessage.typing(sender, "global");
            String json = msg.toJson();
            users.forEach((name, h) -> {
                if (!name.equals(sender)) h.sendRaw(json);
            });
        } else {
            // Typing in DM → gửi cho target
            ClientHandler h = users.get(target);
            if (h != null) h.send(ChatMessage.typing(sender, sender));
        }
    }

    public void broadcastTypingStop(String sender, String target) {
        if (target == null) {
            ChatMessage msg = ChatMessage.typingStop(sender, "global");
            String json = msg.toJson();
            users.forEach((name, h) -> {
                if (!name.equals(sender)) h.sendRaw(json);
            });
        } else {
            ClientHandler h = users.get(target);
            if (h != null) h.send(ChatMessage.typingStop(sender, sender));
        }
    }

    // ── User list ─────────────────────────────────────────────────

    private void broadcastUserList() {
        String json = buildUserListJson();
        users.values().forEach(h -> h.sendRaw(json));
    }

    private String buildUserListJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"USER_LIST\",\"sender\":\"SERVER\",")
                .append("\"to\":null,\"content\":\"\",\"timestamp\":\"\",\"id\":\"\",")
                .append("\"users\":[");

        List<String> names = new ArrayList<>(users.keySet());
        for (int i = 0; i < names.size(); i++) {
            sb.append("\"").append(ChatMessage.escapeJson(names.get(i))).append("\"");
            if (i < names.size() - 1) sb.append(',');
        }
        sb.append("]}");
        return sb.toString();
    }

    // ── Welcome JSON ──────────────────────────────────────────────

    private String buildWelcomeJson(String forDisplayKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"WELCOME\",\"sender\":\"SERVER\",")
                .append("\"to\":\"").append(ChatMessage.escapeJson(forDisplayKey)).append("\",")
                .append("\"content\":\"\",\"timestamp\":\"\",\"id\":\"\",")
                .append("\"you\":\"").append(ChatMessage.escapeJson(forDisplayKey)).append("\",")
                .append("\"users\":[");

        List<String> keys = new ArrayList<>(users.keySet());
        for (int i = 0; i < keys.size(); i++) {
            sb.append("\"").append(ChatMessage.escapeJson(keys.get(i))).append("\"");
            if (i < keys.size() - 1) sb.append(',');
        }

        sb.append("],\"history\":[");
        List<ChatMessage> hist = new ArrayList<>(globalHistory);
        for (int i = 0; i < hist.size(); i++) {
            sb.append(hist.get(i).toJson());
            if (i < hist.size() - 1) sb.append(',');
        }
        sb.append("]}");
        return sb.toString();
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static String dmKey(String a, String b) {
        String[] sorted = new String[]{ a, b };
        Arrays.sort(sorted);
        return sorted[0] + "::" + sorted[1];
    }

    private static <T> void addToHistory(List<T> list, T item, int max) {
        list.add(item);
        // Trim nếu vượt quá max
        while (list.size() > max) {
            list.remove(0);
        }
    }

    public int getUserCount() { return users.size(); }
    public Set<String> getOnlineUsers() { return users.keySet(); }
}