package chatweb.handler;


import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * WebSocketFrame — tự implement WebSocket framing protocol (RFC 6455).
 *
 * Môn Lập trình mạng: đây là phần QUAN TRỌNG nhất —
 * tự xử lý WebSocket frame mà không dùng thư viện ngoài.
 *
 * Frame format:
 * ┌──┬──┬──────┬──┬─────────────────┬──────────────────────┬──────────┐
 * │FIN│RSV│Opcode│M│  Payload Len    │  Masking Key (4B)    │ Payload  │
 * │ 1 │ 3 │  4   │1│   7 + 16/64    │  (if MASK bit = 1)   │          │
 * └──┴──┴──────┴──┴─────────────────┴──────────────────────┴──────────┘
 */
public class WebSocketFrame {

    // Opcodes
    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT         = 0x1;
    public static final int OP_BINARY       = 0x2;
    public static final int OP_CLOSE        = 0x8;
    public static final int OP_PING         = 0x9;
    public static final int OP_PONG         = 0xA;

    private final int     opcode;
    private final boolean fin;
    private final byte[]  payload;

    public WebSocketFrame(int opcode, boolean fin, byte[] payload) {
        this.opcode  = opcode;
        this.fin     = fin;
        this.payload = payload;
    }

    public int    getOpcode()  { return opcode; }
    public boolean isFin()     { return fin; }
    public byte[] getPayload() { return payload; }
    public boolean isText()    { return opcode == OP_TEXT; }
    public boolean isClose()   { return opcode == OP_CLOSE; }
    public boolean isPing()    { return opcode == OP_PING; }

    public String getTextPayload() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    // ── Đọc frame từ InputStream (client → server) ────────────────

    /**
     * Đọc 1 WebSocket frame từ stream.
     * Client frame luôn có mask = 1 (theo RFC 6455).
     * Ném EOFException nếu kết nối đóng.
     */
    public static WebSocketFrame read(InputStream in) throws IOException {
        // Byte 0: FIN + RSV + Opcode
        int b0 = in.read();
        if (b0 < 0) throw new EOFException("Connection closed");

        boolean fin    = (b0 & 0x80) != 0;
        int     opcode = (b0 & 0x0F);

        // Byte 1: MASK + Payload length
        int b1   = in.read();
        if (b1 < 0) throw new EOFException("Connection closed");
        boolean masked = (b1 & 0x80) != 0;
        int payLen = (b1 & 0x7F);

        // Extended payload length
        if (payLen == 126) {
            // 2 bytes extended
            payLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
        } else if (payLen == 127) {
            // 8 bytes extended (chỉ dùng 4 bytes thấp — giới hạn 2GB)
            long len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | (in.read() & 0xFF);
            }
            if (len > Integer.MAX_VALUE) throw new IOException("Frame quá lớn");
            payLen = (int) len;
        }

        // Masking key (4 bytes, chỉ có nếu mask bit = 1)
        byte[] maskKey = new byte[4];
        if (masked) {
            int read = 0;
            while (read < 4) {
                int r = in.read(maskKey, read, 4 - read);
                if (r < 0) throw new EOFException();
                read += r;
            }
        }

        // Payload
        byte[] payload = new byte[payLen];
        int read = 0;
        while (read < payLen) {
            int r = in.read(payload, read, payLen - read);
            if (r < 0) throw new EOFException();
            read += r;
        }

        // Unmask payload
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i % 4];
            }
        }

        return new WebSocketFrame(opcode, fin, payload);
    }

    // ── Ghi frame ra OutputStream (server → client) ───────────────

    /**
     * Ghi 1 WebSocket text frame ra stream.
     * Server frame KHÔNG dùng mask (theo RFC 6455).
     */
    public static void writeText(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        writeFrame(out, OP_TEXT, payload);
    }

    public static void writePong(OutputStream out, byte[] data) throws IOException {
        writeFrame(out, OP_PONG, data);
    }

    public static void writeClose(OutputStream out) throws IOException {
        writeFrame(out, OP_CLOSE, new byte[0]);
    }

    private static void writeFrame(OutputStream out, int opcode, byte[] payload)
            throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // Byte 0: FIN=1 + opcode
        buf.write(0x80 | opcode);

        // Byte 1+: payload length (no mask for server)
        int len = payload.length;
        if (len <= 125) {
            buf.write(len);
        } else if (len <= 65535) {
            buf.write(126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(127);
            for (int i = 7; i >= 0; i--) {
                buf.write((int)((len >> (i * 8)) & 0xFF));
            }
        }

        buf.write(payload);

        // Ghi atomic để tránh interleave giữa các thread
        synchronized (out) {
            out.write(buf.toByteArray());
            out.flush();
        }
    }
}