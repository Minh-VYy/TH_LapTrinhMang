package chatweb.handler;

import chatweb.model.ChatMessage;
import chatweb.server.ChatRoom;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * ClientHandler — xử lý 1 kết nối WebSocket trong 1 Thread riêng.
 *
 * Vòng đời:
 *   1. HTTP Upgrade handshake (tự implement theo RFC 6455)
 *   2. Vòng lặp đọc frame → dispatch message
 *   3. Cleanup khi disconnect
 *
 * Môn Lập trình mạng:
 *   - Dùng java.net.Socket (TCP thuần)
 *   - Tự implement HTTP parser
 *   - Tự implement WebSocket handshake (SHA-1 + Base64)
 *   - Mỗi client = 1 Thread (Thread-per-connection model)
 */
public class ClientHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());
    private static final int MAX_TEXT_LENGTH = 2000;
    private static final String MEDIA_PREFIX = "__MC_MEDIA__|";

    // WebSocket magic GUID theo RFC 6455
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final Socket   socket;
    private final ChatRoom room;

    private InputStream  in;
    private OutputStream out;

    private String  username  = null;
    /** ID ngắn 6 ký tự hex — phân biệt khi tên trùng, dùng để tô màu avatar */
    private String  userId    = null;
    private boolean connected = false;

    public ClientHandler(Socket socket, ChatRoom room) {
        this.socket = socket;
        this.room   = room;
    }

    // ─────────────────────────────────────────────────────────────
    // Main run loop
    // ─────────────────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            in  = socket.getInputStream();
            out = socket.getOutputStream();

            // 1. Parse HTTP request + WebSocket handshake
            String wsKey = parseHttpHandshake();

            if (wsKey == null) {
                // Không phải WebSocket request → trả về HTTP thông thường
                serveHttp();
                return;
            }

            // 2. Gửi HTTP 101 Switching Protocols
            sendHandshakeResponse(wsKey);
            connected = true;
            LOG.info("WebSocket connected: " + socket.getRemoteSocketAddress());

            // 3. Vòng lặp đọc WebSocket frame
            while (connected && !socket.isClosed()) {
                WebSocketFrame frame = WebSocketFrame.read(in);

                if (frame.isClose()) {
                    LOG.info("Client gửi CLOSE frame");
                    break;
                }

                if (frame.isPing()) {
                    WebSocketFrame.writePong(out, frame.getPayload());
                    continue;
                }

                if (frame.isText()) {
                    handleTextFrame(frame.getTextPayload());
                }
            }

        } catch (EOFException | java.net.SocketException e) {
            // Client đóng kết nối đột ngột — bình thường
        } catch (Exception e) {
            LOG.warning("Lỗi client " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP Handshake
    // ─────────────────────────────────────────────────────────────

    /**
     * Đọc HTTP request, trả về Sec-WebSocket-Key nếu là WS Upgrade.
     * Null nếu là HTTP request thông thường.
     */
    private String parseHttpHandshake() throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));

        // Đọc request line
        String requestLine = reader.readLine();
        if (requestLine == null) return null;

        LOG.fine("Request: " + requestLine);

        String   wsKey        = null;
        boolean  isUpgrade    = false;
        String   requestPath  = "/";

        // Parse "GET /path HTTP/1.1"
        if (requestLine.startsWith("GET ")) {
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) requestPath = parts[1];
        }

        // Đọc headers
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            String lower = line.toLowerCase();
            if (lower.startsWith("sec-websocket-key:")) {
                wsKey = line.substring(line.indexOf(':') + 1).trim();
            }
            if (lower.contains("upgrade") && lower.contains("websocket")) {
                isUpgrade = true;
            }
        }

        return (isUpgrade && wsKey != null) ? wsKey : null;
    }

    /**
     * Gửi HTTP 101 Switching Protocols với Sec-WebSocket-Accept đúng chuẩn RFC 6455.
     * Tính SHA-1(key + GUID) rồi Base64 — đây là phần học quan trọng.
     */
    private void sendHandshakeResponse(String wsKey) throws Exception {
        // Tính Sec-WebSocket-Accept = Base64(SHA1(key + GUID))
        String  combined = wsKey + WS_GUID;
        byte[]  sha1     = MessageDigest.getInstance("SHA-1")
                .digest(combined.getBytes(StandardCharsets.UTF_8));
        String  accept   = Base64.getEncoder().encodeToString(sha1);

        String response =
                "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + accept + "\r\n" +
                        "\r\n";

        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();
        LOG.fine("Handshake response sent, accept=" + accept);
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP static file server (phục vụ client/public)
    // ─────────────────────────────────────────────────────────────

    private void serveHttp() throws IOException {
        // Server tự phục vụ index.html
        File htmlFile = new File("client/public/index.html");

        if (!htmlFile.exists()) {
            String body = "<h1>MultiChat Server</h1><p>Server is running.</p>";
            String resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                    "Content-Length: " + body.length() + "\r\n\r\n" + body;
            out.write(resp.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        byte[] content = readFile(htmlFile);
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.flush();
    }

    private byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            return fis.readAllBytes();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Message dispatch
    // ─────────────────────────────────────────────────────────────

    private void handleTextFrame(String json) {
        try {
            ChatMessage msg = ChatMessage.fromJson(json);
            if (msg == null || msg.getType() == null) return;

            switch (msg.getType()) {
                case GLOBAL     -> handleGlobal(msg);
                case DM         -> handleDM(msg);
                case TYPING     -> handleTyping(msg, true);
                case TYPING_STOP-> handleTyping(msg, false);
                case DM_HISTORY -> handleDMHistory(msg);
                default         -> LOG.warning("Unknown type: " + msg.getType());
            }
        } catch (Exception e) {
            LOG.warning("Parse error: " + e.getMessage() + " | json=" + json);
            sendError("Lỗi xử lý tin nhắn.");
        }
    }

    private void handleGlobal(ChatMessage msg) {
        if (username == null) {
            // Lần đầu gửi GLOBAL → lệnh JOIN
            String name = msg.getContent().trim();
            if (name.isEmpty() || name.length() > 24) {
                sendError("Tên không hợp lệ (1–24 ký tự).");
                return;
            }
            // Tạo ID ngắn 6 ký tự — cho phép tên trùng, ID luôn khác nhau
            this.userId   = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            this.username = name;
            // Key trong ChatRoom = "displayName#userId" → không bao giờ trùng
            String uniqueKey = name + "#" + userId;
            room.registerUser(uniqueKey, this);   // luôn thành công vì UUID
            room.onUserJoined(this);
            return;
        }
        // Tin nhắn phòng chung
        String content = normalizeContent(msg.getContent());
        if (content == null || content.isBlank()) return;
        room.broadcastGlobal(ChatMessage.global(getDisplayKey(), content));
    }

    private void handleDM(ChatMessage msg) {
        if (username == null) return;
        String to = msg.getTo();
        String content = normalizeContent(msg.getContent());
        if (to == null || content == null || content.isBlank()) return;
        room.sendDM(ChatMessage.dm(getDisplayKey(), to, content));
    }

    private void handleTyping(ChatMessage msg, boolean start) {
        if (username == null) return;
        String target = msg.getTo();
        if (start) room.broadcastTyping(getDisplayKey(), target);
        else       room.broadcastTypingStop(getDisplayKey(), target);
    }

    private void handleDMHistory(ChatMessage msg) {
        if (username == null) return;
        String other = msg.getTo();
        if (other == null) return;
        room.sendDMHistory(this, other);
    }

    // ─────────────────────────────────────────────────────────────
    // Send helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Gửi 1 ChatMessage tới client này.
     * Thread-safe nhờ synchronized trong WebSocketFrame.writeText.
     */
    public void send(ChatMessage msg) {
        if (!connected || socket.isClosed()) return;
        try {
            WebSocketFrame.writeText(out, msg.toJson());
        } catch (IOException e) {
            LOG.fine("Gửi thất bại tới " + username + ": " + e.getMessage());
            connected = false;
        }
    }

    /**
     * Gửi JSON string tùy ý (dùng cho user list, welcome, v.v.)
     */
    public void sendRaw(String json) {
        if (!connected || socket.isClosed()) return;
        try {
            WebSocketFrame.writeText(out, json);
        } catch (IOException e) {
            connected = false;
        }
    }

    private void sendError(String msg) {
        send(ChatMessage.error(msg));
    }

    private String normalizeContent(String content) {
        if (content == null) return null;
        if (content.startsWith(MEDIA_PREFIX)) return content;
        content = content.trim();
        if (content.length() > MAX_TEXT_LENGTH) {
            content = content.substring(0, MAX_TEXT_LENGTH);
        }
        return content;
    }

    // ─────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────

    private void cleanup() {
        connected = false;
        if (username != null) {
            room.onUserLeft(this);
        }
        try {
            if (!socket.isClosed()) {
                WebSocketFrame.writeClose(out);
                socket.close();
            }
        } catch (Exception ignored) {}
        LOG.info("Disconnected: " + (username != null ? getDisplayKey() : socket.getRemoteSocketAddress()));
    }

    // ── Getters ───────────────────────────────────────────────────
    public String  getUsername()   { return username; }
    public String  getUserId()     { return userId; }
    /** "Name#id6" — key dùng trong ChatRoom và hiển thị cho client */
    public String  getDisplayKey() { return username + "#" + userId; }
    public boolean isConnected()   { return connected; }
}