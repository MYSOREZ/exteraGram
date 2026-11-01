package com.exteragram.messenger.tgws;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Минимальный клиент веб-сокета: TLS к kwsN.<домен>:443, апгрейд на /apiws и двоичные кадры.
 * Ничего лишнего — ровно то, что говорит Telegram Web со своими точками.
 */
final class TgwsWebSocket {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SSLSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean closed;

    private TgwsWebSocket(SSLSocket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    static final class HandshakeException extends IOException {
        final int status;

        HandshakeException(int status, String line) {
            super("HTTP " + status + ": " + line);
            this.status = status;
        }
    }

    static TgwsWebSocket connect(String host, int timeoutMs) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        // Сначала IPv4: у мобильных операторов IPv6 бывает «есть, но не везёт».
        List<InetAddress> order = new ArrayList<>();
        for (InetAddress a : all) {
            if (a instanceof Inet4Address) {
                order.add(a);
            }
        }
        for (InetAddress a : all) {
            if (!(a instanceof Inet4Address)) {
                order.add(a);
            }
        }
        IOException last = null;
        int tries = 0;
        for (InetAddress addr : order) {
            if (tries++ >= 2) {
                break;
            }
            Socket raw = new Socket();
            try {
                raw.setTcpNoDelay(true);
                raw.connect(new InetSocketAddress(addr, 443), timeoutMs);
                return handshake(raw, host, timeoutMs);
            } catch (HandshakeException e) {
                closeQuietly(raw);
                throw e; // сервер ответил — другой адрес того же имени ответит так же
            } catch (IOException e) {
                closeQuietly(raw);
                last = e;
            }
        }
        throw last != null ? last : new IOException("no address for " + host);
    }

    private static TgwsWebSocket handshake(Socket raw, String host, int timeoutMs) throws IOException {
        raw.setSoTimeout(timeoutMs);
        SSLSocketFactory f = HttpsURLConnection.getDefaultSSLSocketFactory();
        SSLSocket s = (SSLSocket) f.createSocket(raw, host, 443, true);
        s.setUseClientMode(true);
        s.startHandshake();
        HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
        if (!hv.verify(host, s.getSession())) {
            closeQuietly(s);
            throw new IOException("bad certificate for " + host);
        }
        byte[] keyBytes = new byte[16];
        RANDOM.nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        String req = "GET /apiws HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Protocol: binary\r\n"
                + "\r\n";
        OutputStream out = s.getOutputStream();
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
        String status = readLine(in);
        int code = 0;
        String[] parts = status.split(" ");
        if (parts.length >= 2) {
            try {
                code = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignore) {
            }
        }
        while (true) {
            String line = readLine(in);
            if (line.isEmpty()) {
                break;
            }
        }
        if (code != 101) {
            closeQuietly(s);
            throw new HandshakeException(code, status);
        }
        s.setSoTimeout(0);
        return new TgwsWebSocket(s, in, out);
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new EOFException("handshake: connection closed");
            }
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
            if (b.size() > 8192) {
                throw new IOException("handshake: line too long");
            }
        }
        return b.toString("ISO-8859-1");
    }

    void setReadTimeout(int ms) {
        try {
            socket.setSoTimeout(ms);
        } catch (IOException ignore) {
        }
    }

    void sendBinary(byte[] data, int off, int len) throws IOException {
        sendFrame(0x2, data, off, len);
    }

    private void sendFrame(int opcode, byte[] data, int off, int len) throws IOException {
        int headerLen = 2 + 4 + (len < 126 ? 0 : len < 65536 ? 2 : 8);
        byte[] frame = new byte[headerLen + len];
        int p = 0;
        frame[p++] = (byte) (0x80 | opcode);
        if (len < 126) {
            frame[p++] = (byte) (0x80 | len);
        } else if (len < 65536) {
            frame[p++] = (byte) (0x80 | 126);
            frame[p++] = (byte) (len >> 8);
            frame[p++] = (byte) len;
        } else {
            frame[p++] = (byte) (0x80 | 127);
            long l = len;
            for (int i = 7; i >= 0; i--) {
                frame[p++] = (byte) (l >> (8 * i));
            }
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        System.arraycopy(mask, 0, frame, p, 4);
        p += 4;
        for (int i = 0; i < len; i++) {
            frame[p + i] = (byte) (data[off + i] ^ mask[i & 3]);
        }
        synchronized (writeLock) {
            out.write(frame);
            out.flush();
        }
    }

    /**
     * Следующее сообщение с данными. null — сокет закрыт. Пинги отвечаются здесь же,
     * фрагменты склеиваются.
     */
    byte[] receive() throws IOException {
        ByteArrayOutputStream frag = null;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) {
                return null;
            }
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = (readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte();
                }
            }
            if (len < 0 || len > 16 * 1024 * 1024) {
                throw new IOException("ws: frame too large");
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            switch (opcode) {
                case 0x8: // close
                    return null;
                case 0x9: // ping
                    sendFrame(0xA, payload, 0, payload.length);
                    continue;
                case 0xA: // pong
                    continue;
                case 0x0: // продолжение
                    if (frag == null) {
                        frag = new ByteArrayOutputStream();
                    }
                    frag.write(payload);
                    if (fin) {
                        return frag.toByteArray();
                    }
                    continue;
                default:
                    if (fin && frag == null) {
                        return payload;
                    }
                    frag = new ByteArrayOutputStream();
                    frag.write(payload);
                    if (fin) {
                        return frag.toByteArray();
                    }
            }
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private void readFully(byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) {
                throw new EOFException();
            }
            n += r;
        }
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            sendFrame(0x8, new byte[0], 0, 0);
        } catch (Throwable ignore) {
        }
        closeQuietly(socket);
    }

    static void closeQuietly(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (Throwable ignore) {
        }
    }
}
