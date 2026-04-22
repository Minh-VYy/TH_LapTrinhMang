package chatweb.server;

import chatweb.handler.ClientHandler;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.*;

/**
 * ChatServer — điểm khởi động của server.
 *
 * Mô hình Thread-per-connection:
 *   - 1 thread chính: lắng nghe kết nối mới (accept loop)
 *   - Mỗi client kết nối → tạo 1 ClientHandler thread mới
 *   - Dùng ThreadPool để giới hạn số thread tối đa
 *
 * Cổng mặc định: 8080 (hoặc biến môi trường PORT khi deploy)
 */
public class ChatServer {

    private static final Logger LOG = Logger.getLogger(ChatServer.class.getName());

    private final int  port;
    private final ChatRoom    room;
    private final ExecutorService pool;

    public ChatServer(int port) {
        this.port = port;
        this.room = new ChatRoom();
        // ThreadPool: tối đa 200 client đồng thời
        this.pool = Executors.newFixedThreadPool(200);
    }

    // ─────────────────────────────────────────────────────────────
    // Start server
    // ─────────────────────────────────────────────────────────────

    public void start() throws IOException {
        // SO_REUSEADDR: tái sử dụng port ngay sau khi restart
        ServerSocket serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);

        printBanner();

        LOG.info("Server lắng nghe tại port " + port);

        // Shutdown hook — dọn dẹp khi Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Server đang tắt...");
            pool.shutdownNow();
            try { serverSocket.close(); } catch (IOException ignored) {}
        }));

        // ── Accept loop ───────────────────────────────────────────
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();

                // Cấu hình socket
                client.setKeepAlive(true);
                client.setSoTimeout(120_000); // 2 phút timeout

                LOG.info("Kết nối mới: " + client.getRemoteSocketAddress()
                        + " | Online: " + room.getUserCount());

                // Giao cho thread pool xử lý
                pool.execute(new ClientHandler(client, room));

            } catch (IOException e) {
                if (serverSocket.isClosed()) break;
                LOG.warning("Accept error: " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Banner
    // ─────────────────────────────────────────────────────────────

    private void printBanner() {
        System.out.println("\n" +
                "  ╔══════════════════════════════════════════╗\n" +
                "  ║          ⬡  MultiChat Server             ║\n" +
                "  ║    Java ServerSocket + WebSocket RFC6455  ║\n" +
                "  ╠══════════════════════════════════════════╣\n" +
                "  ║  Port    : " + String.format("%-30s", port) + "  ║\n" +
                "  ║  Web UI  : " + String.format("%-30s", "http://localhost:" + port) + "  ║\n" +
                "  ║  Protocol: WebSocket (tự implement)       ║\n" +
                "  ╚══════════════════════════════════════════╝\n"
        );
    }

    // ─────────────────────────────────────────────────────────────
    // Main
    // ─────────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        // Cấu hình logging format gọn
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tH:%1$tM:%1$tS] %4$s %3$s — %5$s%n");

        // Đọc port từ biến môi trường PORT (khi deploy cloud)
        // hoặc dùng 8080 mặc định khi chạy local
        int port = 8080;
        try {
            String envPort = System.getenv("PORT");
            if (envPort != null && !envPort.isBlank()) {
                port = Integer.parseInt(envPort.trim());
            } else if (args.length > 0) {
                port = Integer.parseInt(args[0]);
            }
        } catch (NumberFormatException e) {
            System.err.println("Port không hợp lệ, dùng mặc định 8080");
        }

        new ChatServer(port).start();
    }
}