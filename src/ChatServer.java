import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * ChatServer — run this first.
 * Listens on port 5000, accepts multiple clients, and broadcasts
 * chat messages + typing indicators to everyone else.
 *
 * Protocol (plain text lines):
 *   JOIN:<name>              – client announces their name
 *   MSG:<name>:<flag>:<text> – a chat message; <flag> is FAB:GREEN or FAB:RED
 *   TYPING:<name>            – sender is currently typing
 *   STOP_TYPING:<name>       – sender stopped typing
 *   LEFT:<name>              – client disconnected
 */
public class ChatServer {

    private static final int PORT = 5000;
    private static final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws IOException {
        System.out.println("╔══════════════════════════════╗");
        System.out.println("║   Java Chat Server v1.0      ║");
        System.out.println("╚══════════════════════════════╝");
        System.out.println("Listening on port " + PORT + " ...\n");

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(socket);
                clients.add(handler);
                new Thread(handler).start();
            }
        }
    }

    /** Send a line to every client except the one who sent it. */
    static void broadcast(String message, ClientHandler sender) {
        for (ClientHandler c : clients) {
            if (c != sender) {
                c.send(message);
            }
        }
    }

    /** Send a line to every client including the sender (used for join/leave). */
    static void broadcastAll(String message) {
        for (ClientHandler c : clients) {
            c.send(message);
        }
    }

    static void removeClient(ClientHandler handler) {
        clients.remove(handler);
    }

    // ── inner class ──────────────────────────────────────────────────────────

    static class ClientHandler implements Runnable {
        private final Socket socket;
        private PrintWriter out;
        private String name = "Unknown";

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        void send(String line) {
            if (out != null) out.println(line);
        }

        @Override
        public void run() {
            try (
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
            ) {
                out = new PrintWriter(
                        new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())),
                        true   // auto-flush
                );

                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("JOIN:")) {
                        name = line.substring(5).trim();
                        System.out.println("[+] " + name + " joined  (" + socket.getInetAddress() + ")");
                        broadcastAll("JOIN:" + name);

                    } else if (line.startsWith("MSG:")) {
                        System.out.println("[MSG] " + line.substring(4));
                        broadcast(line, this);   // relay to others

                    } else if (line.startsWith("TYPING:") || line.startsWith("STOP_TYPING:")) {
                        broadcast(line, this);   // relay typing state

                    }
                }
            } catch (IOException e) {
                // client disconnected
            } finally {
                removeClient(this);
                System.out.println("[-] " + name + " left");
                broadcastAll("LEFT:" + name);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}