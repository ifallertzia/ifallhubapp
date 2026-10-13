package com.ifallhub.app;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tiny loopback HTTP server used to feed FFmpegKit transcodes into the
 * WebView &lt;video&gt; element.
 *
 * <p>FFmpeg writes a fragmented MP4 ({@code -movflags frag_keyframe+empty_moov})
 * to a growing cache file. This server tails that file and streams the bytes to
 * the player as they appear — progressive playback of a live transcode, which
 * the Chromium media stack handles well.</p>
 */
public class LocalStreamServer {

    private static final String TAG = "IfallhubStreamServer";
    private static final long MAX_TAIL_WAIT_MS = 45_000L;

    private final File file;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile Thread clientThread;

    public LocalStreamServer(File file) {
        this.file = file;
    }

    /** Starts the server on 127.0.0.1 with an ephemeral port. */
    public synchronized int start() throws IOException {
        if (running.get() && serverSocket != null) return serverSocket.getLocalPort();
        serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        finished.set(false);
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "ifallhub-stream-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return serverSocket.getLocalPort();
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : 0;
    }

    public String getPlaybackUrl() {
        return "http://127.0.0.1:" + getPort() + "/live.mp4";
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Marks the growing media file complete so connected clients receive EOF. */
    public void finish() {
        finished.set(true);
    }

    /** Stops the server and closes any streaming client. */
    public synchronized void stop() {
        running.set(false);
        Thread active = clientThread;
        if (active != null) {
            try {
                active.interrupt();
            } catch (Exception ignored) {
            }
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket client = null;
            try {
                client = serverSocket.accept();
                handleClient(client);
            } catch (SocketException closed) {
                break;
            } catch (Exception error) {
                Log.w(TAG, "accept failed", error);
            }
        }
    }

    private void handleClient(Socket client) throws IOException {
        // Read the request line + headers (ignore everything except Range).
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = reader.readLine();
        if (requestLine == null) {
            client.close();
            return;
        }
        long rangeStart = 0L;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase().startsWith("range:")) {
                // bytes=N- | bytes=N-M
                String spec = line.substring(6).trim();
                if (spec.startsWith("bytes=")) {
                    String[] parts = spec.substring(6).split("-", 2);
                    try {
                        rangeStart = Long.parseLong(parts[0].trim());
                    } catch (NumberFormatException ignored) {
                        rangeStart = 0L;
                    }
                }
            }
        }

        final long startOffset = Math.max(0L, rangeStart);
        final Socket socket = client;
        Thread stream = new Thread(() -> streamFile(socket, startOffset), "ifallhub-stream-client");
        stream.setDaemon(true);
        clientThread = stream;
        stream.start();
    }

    private void streamFile(Socket socket, long startOffset) {
        try (Socket auto = socket;
             RandomAccessFile raf = new RandomAccessFile(file, "r")) {

            OutputStream out = auto.getOutputStream();

            // Wait until the transcoder has produced at least the header bytes.
            long waited = 0L;
            while (running.get() && !finished.get()
                    && raf.length() <= startOffset && waited < MAX_TAIL_WAIT_MS) {
                Thread.sleep(200L);
                waited += 200L;
            }

            long length = raf.length();

            StringBuilder head = new StringBuilder();
            if (startOffset > 0 && length > startOffset) {
                head.append("HTTP/1.1 206 Partial Content\r\n");
                head.append("Content-Type: video/mp4\r\n");
                head.append("Accept-Ranges: bytes\r\n");
                head.append("Content-Range: bytes ").append(startOffset).append("-")
                        .append(length - 1).append("/*\r\n");
            } else {
                head.append("HTTP/1.1 200 OK\r\n");
                head.append("Content-Type: video/mp4\r\n");
                head.append("Accept-Ranges: bytes\r\n");
            }
            head.append("Cache-Control: no-store\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            raf.seek(startOffset);
            byte[] buffer = new byte[64 * 1024];
            long position = startOffset;

            while (running.get()) {
                int read = raf.read(buffer);
                if (read > 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                    position += read;
                } else if (read < 0) {
                    // Tail a live file, but close the response once FFmpeg has
                    // completed so the media element can detect end-of-stream.
                    if (!running.get() || finished.get()) break;
                    Thread.sleep(120L);
                } else {
                    Thread.sleep(120L);
                }
                // Re-sync size for logging only.
                if (position > length) length = position;
            }

            // If the transcode finished (running==false) flush whatever is left.
            if (!running.get()) {
                int read;
                while ((read = raf.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            // Client disconnected or file removed — normal during rotation.
            Log.d(TAG, "stream closed: " + error.getMessage());
        }
    }
}
