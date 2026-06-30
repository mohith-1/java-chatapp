import javax.swing.*;
import javax.swing.border.*;
import javax.sound.sampled.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

/**
 * ChatClient — open as many instances as you like.
 * Each instance prompts for a name, then connects to localhost:5000.
 *
 * NEW IN THIS VERSION:
 *   🎤 Voice input  — click the mic, speak, click again, your words appear
 *                      in the text box (uses OpenAI Whisper API for transcription)
 *   🔊 Text-to-speech — toggle button reads incoming messages aloud
 *                      (uses Windows' built-in SAPI voice, no API key needed)
 *   🖼️ Custom background — pick any image from your PC as the chat background
 *   🚩 FAB indicator   — "Flip-Flop Alert Badge". Per-message, not session-wide:
 *                      if the sender typed something, fully cleared the box,
 *                      and then typed something different before sending THAT
 *                      message, the badge shows red for that message only.
 *                      Resets to green automatically for their next message.
 *
 * SETUP REQUIRED FOR VOICE INPUT:
 *   1. Get a free OpenAI API key at https://platform.openai.com/api-keys
 *   2. Create a file named "openai_key.txt" in the same folder as this project
 *   3. Paste your API key into that file (just the key, nothing else) and save
 *   If the file is missing, the app still runs fine — the mic button will
 *   just show an error when clicked, and everything else works normally.
 *
 * Protocol handled:
 *   Outbound  →  JOIN:<name>  |  MSG:<name>:<flag>:<text>  |  TYPING:<name>  |  STOP_TYPING:<name>
 *   Inbound   ←  JOIN:<name>  |  MSG:<name>:<flag>:<text>  |  TYPING:<name>  |  STOP_TYPING:<name>  |  LEFT:<name>
 *   <flag> is either FAB:GREEN or FAB:RED — reflects whether THIS specific
 *   message was cleared and replaced with different text before being sent.
 */
public class ChatClient {

    // ── Connection ───────────────────────────────────────────────────────────
    private static final String HOST = "localhost";
    private static final int    PORT = 5000;

    private Socket       socket;
    private PrintWriter  out;
    private String       myName;

    // ── Typing-indicator throttle ─────────────────────────────────────────
    private volatile boolean typingStateSent = false;
    private javax.swing.Timer stopTypingTimer;

    // ── Voice features ──────────────────────────────────────────────────────
    private String openAiKey = null;            // loaded from openai_key.txt
    private volatile boolean isRecording = false;
    private TargetDataLine micLine;
    private ByteArrayOutputStream recordedAudio;
    private AudioFormat audioFormat;
    private volatile boolean ttsEnabled = false; // toggled by the speaker button

    // ── FAB (Flip-Flop Alert Badge) tracking ─────────────────────────────────
    private String preClearText = "";               // text that was cleared (locked in once box goes empty)
    private String longestSeenThisMessage = "";      // longest text reached since the last send
    private boolean myFabTriggered = false;          // true if THIS message was cleared and replaced with different text

    // ── Background image ─────────────────────────────────────────────────────
    private BufferedImage backgroundImage = null;   // null = default plain background
    private JPanel rootPanel;                        // custom-painted panel that draws the background

    // ── Swing components ─────────────────────────────────────────────────────
    private JFrame     frame;
    private JTextPane  chatPane;
    private JTextField inputField;
    private JLabel     typingLabel;
    private JButton    sendButton;
    private JButton    micButton;
    private JButton    ttsButton;
    private JButton    bgButton;
    private JLabel     fabIndicator;   // small colored dot showing the OTHER person's FAB status for their CURRENT message

    // Colours
    private static final Color BG_WINDOW   = new Color(245, 245, 247);
    private static final Color BG_CHAT     = new Color(255, 255, 255);
    private static final Color COLOR_INFO  = new Color(142, 142, 147);
    private static final Color COLOR_ACCENT = new Color(0, 122, 255);
    private static final Color COLOR_RECORDING = new Color(255, 59, 48);
    private static final Color COLOR_TTS_ON = new Color(52, 199, 89);
    private static final Color COLOR_FAB_GREEN = new Color(52, 199, 89);
    private static final Color COLOR_FAB_RED = new Color(255, 59, 48);

    // ── Entry point ───────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new ChatClient().start();
        });
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────
    private void start() {
        loadApiKey();
        myName = askForName();
        if (myName == null || myName.isBlank()) {
            System.exit(0);
        }

        buildUI();
        connectToServer();
    }

    /** Reads the OpenAI API key from openai_key.txt in the project root, if present. */
    private void loadApiKey() {
        try {
            File f = new File("openai_key.txt");
            if (f.exists()) {
                String content = Files.readString(f.toPath(), StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    openAiKey = content;
                }
            }
        } catch (IOException e) {
            // No key file — voice input simply won't work, everything else is fine
        }
    }

    private String askForName() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(12, 16, 4, 16));

        JLabel label = new JLabel("Enter your display name:");
        label.setFont(new Font("SF Pro Text", Font.PLAIN, 13));

        JTextField field = new JTextField(18);
        field.setFont(new Font("SF Pro Text", Font.PLAIN, 14));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(180, 180, 185), 1, true),
                new EmptyBorder(6, 8, 6, 8)));

        panel.add(label, BorderLayout.NORTH);
        panel.add(field,  BorderLayout.CENTER);

        int[] result = {-1};
        field.addActionListener(e -> {
            result[0] = JOptionPane.OK_OPTION;
            SwingUtilities.getWindowAncestor(field).dispose();
        });

        int opt = JOptionPane.showConfirmDialog(
                null, panel,
                "Java Chat", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (result[0] == JOptionPane.OK_OPTION || opt == JOptionPane.OK_OPTION) {
            return field.getText().trim();
        }
        return null;
    }

    // ── GUI construction ──────────────────────────────────────────────────────
    private void buildUI() {
        frame = new JFrame("Java Chat  ·  " + myName);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(480, 640);
        frame.setMinimumSize(new Dimension(360, 440));
        frame.setLocationRelativeTo(null);

        // ── Root panel that paints the background image (or plain colour) ──
        rootPanel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (backgroundImage != null) {
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    // Scale image to cover the whole panel, cropping overflow
                    int panelW = getWidth(), panelH = getHeight();
                    int imgW = backgroundImage.getWidth(), imgH = backgroundImage.getHeight();
                    double scale = Math.max((double) panelW / imgW, (double) panelH / imgH);
                    int drawW = (int) (imgW * scale), drawH = (int) (imgH * scale);
                    int x = (panelW - drawW) / 2, y = (panelH - drawH) / 2;
                    g2.drawImage(backgroundImage, x, y, drawW, drawH, null);
                } else {
                    g.setColor(BG_WINDOW);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            }
        };
        rootPanel.setOpaque(true);
        frame.setContentPane(rootPanel);

        // ── Header bar (semi-transparent so background shows through) ──────
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(255, 255, 255, 235));
        header.setOpaque(true);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(210, 210, 215)),
                new EmptyBorder(12, 16, 12, 16)));

        JLabel title = new JLabel("Group Chat");
        title.setFont(new Font("SF Pro Display", Font.BOLD, 16));
        title.setForeground(new Color(30, 30, 30));

        JLabel subtitle = new JLabel("Connected as " + myName);
        subtitle.setFont(new Font("SF Pro Text", Font.PLAIN, 11));
        subtitle.setForeground(COLOR_INFO);

        JPanel titleGroup = new JPanel(new GridLayout(2, 1, 0, 1));
        titleGroup.setOpaque(false);
        titleGroup.add(title);
        titleGroup.add(subtitle);
        header.add(titleGroup, BorderLayout.WEST);

        // Right side: FAB indicator + background picker + TTS toggle + connection dot
        JPanel rightHeader = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightHeader.setOpaque(false);

        fabIndicator = new JLabel("●");
        fabIndicator.setFont(new Font("SF Pro Text", Font.BOLD, 16));
        fabIndicator.setForeground(COLOR_FAB_GREEN);
        fabIndicator.setToolTipText("Green: their last message was sent as typed. " +
                "Red: for that message, they cleared the box and typed something different before sending.");
        rightHeader.add(fabIndicator);

        bgButton = new JButton("🖼️");
        bgButton.setFont(new Font("SF Pro Text", Font.PLAIN, 13));
        bgButton.setFocusPainted(false);
        bgButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        bgButton.setBorder(new EmptyBorder(4, 8, 4, 8));
        bgButton.setToolTipText("Choose a background image from your device");
        bgButton.addActionListener(e -> chooseBackgroundImage());
        rightHeader.add(bgButton);

        ttsButton = new JButton("🔊 Read Aloud: Off");
        ttsButton.setFont(new Font("SF Pro Text", Font.PLAIN, 11));
        ttsButton.setFocusPainted(false);
        ttsButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ttsButton.setBorder(new EmptyBorder(4, 8, 4, 8));
        ttsButton.addActionListener(e -> toggleTts());
        rightHeader.add(ttsButton);

        JLabel dot = new JLabel("● Connected");
        dot.setFont(new Font("SF Pro Text", Font.PLAIN, 11));
        dot.setForeground(new Color(52, 199, 89));
        rightHeader.add(dot);

        header.add(rightHeader, BorderLayout.EAST);

        rootPanel.add(header, BorderLayout.NORTH);

        // ── Chat pane (transparent background so the image shows through) ──
        chatPane = new JTextPane();
        chatPane.setEditable(false);
        chatPane.setOpaque(false);
        chatPane.setContentType("text/html");
        chatPane.setText("<html><body style='font-family:sans-serif;font-size:13px;margin:8px'></body></html>");

        JScrollPane scrollPane = new JScrollPane(chatPane);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getVerticalScrollBar().setUnitIncrement(12);
        rootPanel.add(scrollPane, BorderLayout.CENTER);

        // ── Typing indicator ─────────────────────────────────────────────
        typingLabel = new JLabel(" ");
        typingLabel.setFont(new Font("SF Pro Text", Font.ITALIC, 12));
        typingLabel.setForeground(COLOR_INFO);
        typingLabel.setBorder(new EmptyBorder(4, 16, 0, 16));

        // ── Input row ─────────────────────────────────────────────────────
        inputField = new JTextField();
        inputField.setFont(new Font("SF Pro Text", Font.PLAIN, 14));
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 205), 1, true),
                new EmptyBorder(8, 12, 8, 12)));

        micButton = new JButton("🎤");
        micButton.setFont(new Font("SF Pro Text", Font.PLAIN, 16));
        micButton.setFocusPainted(false);
        micButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        micButton.setBorder(new EmptyBorder(6, 12, 6, 12));
        micButton.setToolTipText("Click to record, click again to transcribe");
        micButton.addActionListener(e -> toggleRecording());

        sendButton = new JButton("Send");
        sendButton.setFont(new Font("SF Pro Text", Font.BOLD, 13));
        sendButton.setForeground(Color.WHITE);
        sendButton.setBackground(COLOR_ACCENT);
        sendButton.setOpaque(true);
        sendButton.setBorderPainted(false);
        sendButton.setFocusPainted(false);
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.setBorder(new EmptyBorder(8, 18, 8, 18));

        JPanel inputRow = new JPanel(new BorderLayout(8, 0));
        inputRow.setOpaque(false);
        inputRow.setBorder(new EmptyBorder(8, 12, 12, 12));
        inputRow.add(micButton,   BorderLayout.WEST);
        inputRow.add(inputField,  BorderLayout.CENTER);
        inputRow.add(sendButton,  BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(new Color(245, 245, 247, 235));
        bottom.setOpaque(true);
        bottom.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(210, 210, 215)));
        bottom.add(typingLabel, BorderLayout.NORTH);
        bottom.add(inputRow,    BorderLayout.CENTER);

        rootPanel.add(bottom, BorderLayout.SOUTH);

        // ── Send button / Enter key ───────────────────────────────────────
        ActionListener sendAction = e -> sendMessage();
        sendButton.addActionListener(sendAction);
        inputField.addActionListener(sendAction);

        // ── Typing detection ──────────────────────────────────────────────
        stopTypingTimer = new javax.swing.Timer(1200, e -> sendStopTyping());
        stopTypingTimer.setRepeats(false);

        inputField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { onKeystroke(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { onKeystroke(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });

        frame.setVisible(true);
    }

    // ── Background image picker ──────────────────────────────────────────────

    /** Opens a file chooser so the user can pick any image from their own device. */
    private void chooseBackgroundImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a background image");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Image files (jpg, png, gif, bmp)", "jpg", "jpeg", "png", "gif", "bmp"));

        int result = chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            try {
                BufferedImage img = ImageIO.read(selected);
                if (img == null) {
                    JOptionPane.showMessageDialog(frame,
                            "That file doesn't look like a supported image.",
                            "Invalid Image", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                backgroundImage = img;
                rootPanel.repaint();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame,
                        "Could not load that image:\n" + e.getMessage(),
                        "Image Load Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ── Text-to-Speech ───────────────────────────────────────────────────────

    private void toggleTts() {
        ttsEnabled = !ttsEnabled;
        ttsButton.setText(ttsEnabled ? "🔊 Read Aloud: On" : "🔊 Read Aloud: Off");
        ttsButton.setForeground(ttsEnabled ? COLOR_TTS_ON : Color.BLACK);
    }

    /**
     * Speaks the given text aloud using Windows' built-in SAPI voice via PowerShell.
     * Runs on a background thread so the GUI never freezes. Free, no API key, no internet.
     */
    private void speak(String text) {
        if (!ttsEnabled || text == null || text.isBlank()) return;

        new Thread(() -> {
            try {
                String safeText = text.replace("\"", "'");
                String psCommand =
                        "Add-Type -AssemblyName System.Speech; " +
                                "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                                "$s.Speak(\"" + safeText + "\");";

                ProcessBuilder pb = new ProcessBuilder(
                        "powershell.exe", "-Command", psCommand);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                process.waitFor();
            } catch (Exception e) {
                // TTS failure shouldn't crash the app — just skip speaking this message
                System.err.println("TTS error: " + e.getMessage());
            }
        }, "tts-thread").start();
    }

    // ── Voice Input (Recording + Whisper transcription) ────────────────────────

    private void toggleRecording() {
        if (!isRecording) {
            startRecording();
        } else {
            stopRecordingAndTranscribe();
        }
    }

    private void startRecording() {
        if (openAiKey == null) {
            JOptionPane.showMessageDialog(frame,
                    "Voice input needs an OpenAI API key.\n\n" +
                            "Create a file named 'openai_key.txt' in your project folder\n" +
                            "and paste your API key inside it, then restart the app.",
                    "API Key Missing", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            audioFormat = new AudioFormat(16000.0f, 16, 1, true, false); // 16kHz mono PCM, Whisper-friendly
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, audioFormat);

            if (!AudioSystem.isLineSupported(info)) {
                JOptionPane.showMessageDialog(frame,
                        "No microphone found or format not supported.",
                        "Microphone Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            micLine = (TargetDataLine) AudioSystem.getLine(info);
            micLine.open(audioFormat);
            micLine.start();

            recordedAudio = new ByteArrayOutputStream();
            isRecording = true;
            micButton.setText("⏹");
            micButton.setForeground(COLOR_RECORDING);
            typingLabel.setText("Recording... click 🎤 again to stop");

            // Capture audio on a background thread
            new Thread(() -> {
                byte[] buffer = new byte[4096];
                while (isRecording) {
                    int bytesRead = micLine.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        recordedAudio.write(buffer, 0, bytesRead);
                    }
                }
            }, "mic-capture").start();

        } catch (LineUnavailableException e) {
            JOptionPane.showMessageDialog(frame,
                    "Could not access the microphone:\n" + e.getMessage(),
                    "Microphone Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopRecordingAndTranscribe() {
        isRecording = false;
        micButton.setText("🎤");
        micButton.setForeground(Color.BLACK);
        typingLabel.setText("Transcribing...");

        if (micLine != null) {
            micLine.stop();
            micLine.close();
        }

        // Convert raw PCM bytes into a proper WAV file, then send to Whisper
        new Thread(() -> {
            try {
                byte[] pcmData = recordedAudio.toByteArray();
                if (pcmData.length < 1000) {
                    SwingUtilities.invokeLater(() -> typingLabel.setText(" "));
                    return; // too short, likely an accidental click
                }

                File wavFile = pcmToWavFile(pcmData, audioFormat);
                String transcript = transcribeWithWhisper(wavFile);
                wavFile.delete();

                SwingUtilities.invokeLater(() -> {
                    typingLabel.setText(" ");
                    if (transcript != null && !transcript.isBlank()) {
                        inputField.setText(transcript.trim());
                        inputField.requestFocus();
                    }
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    typingLabel.setText(" ");
                    JOptionPane.showMessageDialog(frame,
                            "Transcription failed:\n" + e.getMessage(),
                            "Voice Input Error", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "whisper-upload").start();
    }

    /** Wraps raw PCM bytes in a proper WAV header and writes to a temp file. */
    private File pcmToWavFile(byte[] pcmData, AudioFormat format) throws IOException {
        File tempFile = File.createTempFile("voice_input_", ".wav");
        try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(pcmData), format, pcmData.length / format.getFrameSize())) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, tempFile);
        }
        return tempFile;
    }

    /**
     * Uploads the WAV file to OpenAI's Whisper transcription endpoint
     * (multipart/form-data POST) and returns the transcribed text.
     */
    private String transcribeWithWhisper(File wavFile) throws IOException {
        String boundary = "----JavaChatBoundary" + System.currentTimeMillis();
        URL url = new URL("https://api.openai.com/v1/audio/transcriptions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + openAiKey);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream();
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true)) {

            // model field
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n");
            writer.append("whisper-1\r\n");
            writer.flush();

            // file field
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n");
            writer.append("Content-Type: audio/wav\r\n\r\n");
            writer.flush();

            Files.copy(wavFile.toPath(), os);
            os.flush();

            writer.append("\r\n--").append(boundary).append("--\r\n");
            writer.flush();
        }

        int status = conn.getResponseCode();
        InputStream respStream = (status >= 200 && status < 300)
                ? conn.getInputStream() : conn.getErrorStream();

        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(respStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) response.append(line);
        }

        if (status < 200 || status >= 300) {
            throw new IOException("API returned status " + status + ": " + response);
        }

        // Minimal JSON parsing — extract the "text" field without a JSON library
        String json = response.toString();
        int textIdx = json.indexOf("\"text\"");
        if (textIdx == -1) return null;
        int colonIdx = json.indexOf(':', textIdx);
        int firstQuote = json.indexOf('"', colonIdx + 1);
        int secondQuote = findUnescapedQuote(json, firstQuote + 1);
        if (firstQuote == -1 || secondQuote == -1) return null;

        String raw = json.substring(firstQuote + 1, secondQuote);
        return raw.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\");
    }

    private int findUnescapedQuote(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (s.charAt(i) == '"' && s.charAt(i - 1) != '\\') return i;
        }
        return -1;
    }

    // ── Typing indicator helpers ───────────────────────────────────────────────
    private void onKeystroke() {
        if (!typingStateSent) {
            sendRaw("TYPING:" + myName);
            typingStateSent = true;
        }
        stopTypingTimer.restart();

        // ── FAB tracking for the CURRENT message only ──
        // Goal: detect "typed something substantial, cleared the box fully,
        // then typed something different" before this message gets sent.
        //
        // We track the LONGEST text seen since the last send (handles
        // backspacing one character at a time, not just select-all-delete).
        // The moment the box becomes empty after having reached a
        // meaningful length, we lock that in as "what was cleared".
        // Anything typed afterward that doesn't start the same way trips the flag.
        String current = inputField.getText().trim();

        if (current.length() > longestSeenThisMessage.length()) {
            longestSeenThisMessage = current;
        }

        if (current.isEmpty()) {
            // Box is empty right now. If we'd built up something substantial
            // before this point, lock it in as the cleared draft.
            if (longestSeenThisMessage.length() >= 3 && preClearText.isEmpty()) {
                preClearText = longestSeenThisMessage;
            }

        } else if (!preClearText.isEmpty()) {
            // Typing again after a clear. Compare against what was cleared.
            String prefixCheck = preClearText.substring(0, Math.min(3, preClearText.length()));
            if (!current.toLowerCase().startsWith(prefixCheck.toLowerCase())) {
                myFabTriggered = true;
            }
        }
    }

    private void sendStopTyping() {
        if (typingStateSent) {
            sendRaw("STOP_TYPING:" + myName);
            typingStateSent = false;
        }
    }

    // ── Networking ────────────────────────────────────────────────────────────
    private void connectToServer() {
        try {
            socket = new Socket(HOST, PORT);
            out    = new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);

            sendRaw("JOIN:" + myName);

            new Thread(this::readLoop, "reader").start();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame,
                    "Could not connect to server at " + HOST + ":" + PORT + "\n\n"
                            + "Make sure ChatServer is running first.",
                    "Connection failed", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                final String msg = line;
                SwingUtilities.invokeLater(() -> handleIncoming(msg));
            }
        } catch (IOException e) {
            SwingUtilities.invokeLater(() ->
                    appendInfo("⚠ Disconnected from server."));
        }
    }

    private void sendRaw(String line) {
        if (out != null) out.println(line);
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        inputField.setText("");
        sendStopTyping();

        String flag = myFabTriggered ? "FAB:RED" : "FAB:GREEN";
        String payload = "MSG:" + myName + ":" + flag + ":" + text;
        sendRaw(payload);
        appendMyMessage(text);

        // Reset FAB tracking — this badge is per-message, not session-wide.
        myFabTriggered = false;
        preClearText = "";
        longestSeenThisMessage = "";
    }

    // ── Incoming message dispatcher ───────────────────────────────────────────
    private void handleIncoming(String line) {
        if (line.startsWith("JOIN:")) {
            String who = line.substring(5);
            appendInfo("✦ " + who + " joined the chat");

        } else if (line.startsWith("LEFT:")) {
            String who = line.substring(5);
            typingLabel.setText(" ");
            appendInfo("✦ " + who + " left the chat");

        } else if (line.startsWith("MSG:")) {
            // Format: MSG:<name>:<flag>:<text>  where flag is FAB:GREEN or FAB:RED
            String rest = line.substring(4);
            int firstColon = rest.indexOf(':');
            if (firstColon > 0) {
                String sender = rest.substring(0, firstColon);
                String afterSender = rest.substring(firstColon + 1);

                // afterSender now looks like "FAB:GREEN:actual message text"
                String text = afterSender;
                if (afterSender.startsWith("FAB:")) {
                    int secondColon = afterSender.indexOf(':', 4);
                    if (secondColon > 0) {
                        String flagValue = afterSender.substring(4, secondColon); // GREEN or RED
                        text = afterSender.substring(secondColon + 1);

                        // Reflects THIS message only — green unless this
                        // specific message was cleared and replaced.
                        fabIndicator.setForeground(
                                "RED".equals(flagValue) ? COLOR_FAB_RED : COLOR_FAB_GREEN);
                    }
                }

                typingLabel.setText(" ");
                appendOtherMessage(sender, text);
                speak(sender + " says: " + text);   // 🔊 read aloud if enabled
            }

        } else if (line.startsWith("TYPING:")) {
            String who = line.substring(7);
            typingLabel.setText(who + " is typing…");

        } else if (line.startsWith("STOP_TYPING:")) {
            typingLabel.setText(" ");
        }
    }

    // ── HTML chat bubble helpers ──────────────────────────────────────────────
    private static final SimpleDateFormat TIME_FMT = new SimpleDateFormat("HH:mm");

    private void appendMyMessage(String text) {
        String time   = TIME_FMT.format(new Date());
        String escaped = escapeHtml(text);
        String html =
                "<div style='text-align:right; margin:6px 8px 2px 40px'>" +
                        "  <span style='" +
                        "    display:inline-block;" +
                        "    background:#007AFF;" +
                        "    color:#fff;" +
                        "    padding:8px 12px;" +
                        "    border-radius:18px 18px 4px 18px;" +
                        "    max-width:300px;" +
                        "    word-wrap:break-word;" +
                        "    font-size:13px;'>" +
                        escaped +
                        "  </span>" +
                        "  <div style='color:#8e8e93;font-size:10px;margin-top:2px'>" + time + "</div>" +
                        "</div>";
        appendHtml(html);
    }

    private void appendOtherMessage(String sender, String text) {
        String time    = TIME_FMT.format(new Date());
        String escaped = escapeHtml(text);
        String initials = sender.length() > 0
                ? String.valueOf(sender.charAt(0)).toUpperCase() : "?";

        String html =
                "<div style='text-align:left; margin:6px 40px 2px 8px; display:flex'>" +
                        "  <div style='" +
                        "    display:inline-block;" +
                        "    background:#" + nameToColor(sender) + ";" +
                        "    color:#fff;" +
                        "    width:28px;height:28px;" +
                        "    border-radius:50%;" +
                        "    text-align:center;" +
                        "    line-height:28px;" +
                        "    font-size:12px;font-weight:bold;" +
                        "    margin-right:6px;vertical-align:top;float:left'>" +
                        initials +
                        "  </div>" +
                        "  <div style='display:inline-block'>" +
                        "    <div style='color:#8e8e93;font-size:10px;margin-bottom:2px'>" + escapeHtml(sender) + "</div>" +
                        "    <span style='" +
                        "      display:inline-block;" +
                        "      background:#E5E5EA;" +
                        "      color:#1c1c1e;" +
                        "      padding:8px 12px;" +
                        "      border-radius:18px 18px 18px 4px;" +
                        "      max-width:300px;" +
                        "      word-wrap:break-word;" +
                        "      font-size:13px;'>" +
                        escaped +
                        "    </span>" +
                        "    <div style='color:#8e8e93;font-size:10px;margin-top:2px'>" + time + "</div>" +
                        "  </div>" +
                        "</div>";
        appendHtml(html);
    }

    private void appendInfo(String text) {
        String html =
                "<div style='text-align:center;color:#8e8e93;font-size:11px;" +
                        "margin:8px 0;font-style:italic'>" + escapeHtml(text) + "</div>";
        appendHtml(html);
    }

    private void appendHtml(String snippet) {
        try {
            javax.swing.text.html.HTMLDocument doc =
                    (javax.swing.text.html.HTMLDocument) chatPane.getDocument();
            javax.swing.text.html.HTMLEditorKit kit =
                    (javax.swing.text.html.HTMLEditorKit) chatPane.getEditorKit();
            kit.insertHTML(doc, doc.getLength(), snippet, 0, 0, null);

            chatPane.setCaretPosition(doc.getLength());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String nameToColor(String name) {
        int hash = name.hashCode();
        String[] palette = {
                "5856D6", "FF2D55", "FF9500", "34C759",
                "007AFF", "AF52DE", "00C7BE", "FF6B35"
        };
        return palette[Math.abs(hash) % palette.length];
    }
}