package com.exteragram.messenger.tgws;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * sTGWS внутри клиента: Telegram через веб-сокет без VPN и без настроек.
 *
 * Как это устроено. При старте приложение поднимает у себя на 127.0.0.1 маленький SOCKS5 и
 * сетевое ядро (tgnet) ходит к дата-центрам через него. Мост смотрит, к какому ДЦ идёт
 * соединение, и:
 *   - если этот ДЦ у оператора открыт — пропускает напрямую;
 *   - иначе заворачивает поток MTProto в обычный веб-сокет к kwsN.<домен> за Cloudflare.
 * Оператор видит обычный HTTPS к чужому домену. Шифрование MTProto при этом сквозное: мост
 * ничего не расшифровывает, только перекладывает байты.
 *
 * Подбор (как `tgws pick` на роутере): сначала проверяется, какие ДЦ отвечают напрямую,
 * потом пул доменов — для каждого ДЦ свой лучший. Подбор идёт в фоне при запуске и при смене
 * сети; до его окончания соединения идут через веб-сокет по пулу в исходном порядке.
 *
 * Пользовательский прокси, если он включён в настройках, главнее: тогда мост не используется.
 * Системный VPN не мешает — сокеты моста идут туда же, куда шёл бы сам Telegram.
 */
public final class Tgws {

    public static final String HOST = "127.0.0.1";
    private static final String PREFS_KEY_ENABLED = "tgws_enabled";
    private static final String PREFS_KEY_DOMAINS = "tgws_domains";
    private static final String PREFS_KEY_LEARNED = "tgws_dc_learned";

    /** Пул доменов за Cloudflare — снимок из sTGWS (xyzmean/brb), порядок перестраивается подбором. */
    private static final String[] DEFAULT_DOMAINS = {
            "yo1nk.app",
            "cakeisalie.co.uk",
            "ebally.co.uk",
            "fixtelega.co.uk",
            "havegreatday.co.uk",
            "kartoshka.co.uk",
            "lovetrue.co.uk",
            "nebally.co.uk",
            "noskomnadzor.co.uk",
            "notelega.co.uk",
            "nothingthere.co.uk",
            "offshor.co.uk",
            "onedaychamp.co.uk",
            "pclead.co.uk",
            "pomogite.co.uk",
            "pyatdesyatdva.co.uk",
            "pyatdesyatodin.co.uk",
            "sadnews.co.uk",
            "sorokdva.co.uk",
            "sorokodin.co.uk",
            "stopblocking.co.uk",
    };
    /** Свои точки Telegram Web — тоже кандидат, подбор сам решит, жив ли он у этого оператора. */
    private static final String OFFICIAL = "web.telegram.org";
    private static final String DOMAINS_URL = "https://gitlab.com/xyzmean/brb/-/raw/main/tgws/files/etc/stgws/tgws-domains.lst";

    /** Основные адреса ДЦ — для прямой пробы. */
    private static final String[] DC_MAIN_IP = {null, "149.154.175.50", "149.154.167.51", "149.154.175.100", "149.154.167.91", "149.154.171.5"};

    private static final int CONNECT_TIMEOUT = 5000;
    private static final int WS_TIMEOUT = 6000;
    private static final int DIRECT_ANSWER_TIMEOUT = 7000;
    private static final long FAIL_COOLDOWN_MS = 3 * 60 * 1000L;
    private static final long DIRECT_COOLDOWN_MS = 10 * 60 * 1000L;

    private static final Object lock = new Object();
    private static ServerSocket server;
    private static int port;
    private static String user;
    private static String pass;
    private static final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "tgws-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    // ---- таблица «адрес → дата-центр» ----------------------------------------------------

    private static final class Dc {
        final int id;
        final boolean media;

        Dc(int id, boolean media) {
            this.id = id;
            this.media = media;
        }
    }

    private static final Map<String, Dc> ipToDc = new ConcurrentHashMap<>();

    static {
        String[][] table = {
                {"149.154.175.50", "1"}, {"149.154.175.51", "1"}, {"149.154.175.53", "1"}, {"149.154.175.54", "1"},
                {"149.154.175.211", "1"}, {"149.154.175.205", "1"}, {"149.154.175.52", "-1"},
                {"149.154.167.35", "2"}, {"149.154.167.40", "2"}, {"149.154.167.41", "2"}, {"149.154.167.50", "2"},
                {"149.154.167.51", "2"}, {"149.154.167.255", "2"}, {"149.154.167.220", "2"}, {"95.161.76.100", "2"},
                {"149.154.167.151", "-2"}, {"149.154.167.222", "-2"}, {"149.154.167.223", "-2"}, {"149.154.162.123", "-2"},
                {"149.154.175.100", "3"}, {"149.154.175.101", "3"}, {"149.154.175.102", "-3"},
                {"149.154.167.91", "4"}, {"149.154.167.92", "4"}, {"149.154.164.250", "-4"}, {"149.154.166.120", "-4"},
                {"149.154.166.121", "-4"}, {"149.154.167.118", "-4"}, {"149.154.165.111", "-4"},
                {"91.108.56.100", "5"}, {"91.108.56.101", "5"}, {"91.108.56.116", "5"}, {"91.108.56.126", "5"},
                {"149.154.171.5", "5"}, {"91.108.56.102", "-5"}, {"91.108.56.128", "-5"}, {"91.108.56.151", "-5"},
                {"91.105.192.100", "203"},
        };
        for (String[] r : table) {
            int v = Integer.parseInt(r[1]);
            ipToDc.put(r[0], new Dc(Math.abs(v), v < 0));
        }
    }

    private static Dc lookupDc(String ip) {
        Dc d = ipToDc.get(ip);
        if (d != null) {
            return d;
        }
        String l = ip.toLowerCase();
        if (l.contains(":")) {
            try {
                // Полная форма адреса: префиксы ниже записаны в ней.
                byte[] b = InetAddress.getByName(l).getAddress();
                if (b.length == 16) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 8; i++) {
                        sb.append(Integer.toHexString(((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff))).append(':');
                    }
                    l = sb.toString();
                    d = ipToDc.get(l);
                    if (d != null) {
                        return d;
                    }
                }
            } catch (Exception ignore) {
            }
            if (l.startsWith("2001:b28:f23d:f001:")) return new Dc(1, false);
            if (l.startsWith("2001:67c:4e8:f002:")) return new Dc(2, false);
            if (l.startsWith("2001:b28:f23d:f003:")) return new Dc(3, false);
            if (l.startsWith("2001:67c:4e8:f004:")) return new Dc(4, false);
            if (l.startsWith("2001:b28:f23f:f005:")) return new Dc(5, false);
        }
        return null;
    }

    /** Адреса из help.getConfig: новые адреса ДЦ узнаём от самого Telegram. */
    public static void learnConfig(TLRPC.TL_config config) {
        if (config == null || config.dc_options == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (TLRPC.TL_dcOption o : config.dc_options) {
            if (o == null || o.cdn || TextUtils.isEmpty(o.ip_address) || o.id <= 0) {
                continue;
            }
            if (o.id > 5 && o.id != 203) {
                continue;
            }
            String ip = normalizeIp(o.ip_address);
            ipToDc.put(ip, new Dc(o.id, o.media_only));
            sb.append(ip).append(' ').append(o.media_only ? -o.id : o.id).append('\n');
        }
        if (sb.length() > 0) {
            try {
                prefs().edit().putString(PREFS_KEY_LEARNED, sb.toString()).apply();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String normalizeIp(String ip) {
        if (!ip.contains(":")) {
            return ip;
        }
        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length == 16) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 8; i++) {
                    sb.append(Integer.toHexString(((b[2 * i] & 0xff) << 8) | (b[2 * i + 1] & 0xff))).append(':');
                }
                return sb.toString();
            }
        } catch (Exception ignore) {
        }
        return ip.toLowerCase();
    }

    // ---- состояние маршрутов -------------------------------------------------------------

    /** Пул доменов в порядке «лучший первым». */
    private static volatile List<String> domains = new ArrayList<>();
    /** Для каждого ДЦ (1..5) — домены, ответившие у этого ДЦ, быстрые первыми. */
    private static final Map<Integer, List<String>> perDc = new ConcurrentHashMap<>();
    /** ДЦ, открытые напрямую: до какого момента это считается правдой. */
    private static final Map<Integer, Long> directOk = new ConcurrentHashMap<>();
    /** Точки, отказавшие недавно, — не тратим на них время повторно. */
    private static final Map<String, Long> cooldown = new ConcurrentHashMap<>();

    private static final AtomicBoolean picking = new AtomicBoolean();
    private static volatile boolean pickPending;
    private static volatile long lastPick;
    private static volatile long startedAt;
    private static volatile String status = "ещё не запускался";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        try {
            return prefs().getBoolean(PREFS_KEY_ENABLED, true);
        } catch (Throwable e) {
            return true;
        }
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(PREFS_KEY_ENABLED, enabled).apply();
    }

    public static String getStatus() {
        return status;
    }

    public static int getPort() {
        return port;
    }

    public static String getUser() {
        return user;
    }

    public static String getPass() {
        return pass;
    }

    /** Поднимает мост, если он ещё не поднят. Возвращает порт либо 0. */
    public static int ensureStarted() {
        synchronized (lock) {
            if (server != null && !server.isClosed()) {
                return port;
            }
            try {
                loadState();
                SecureRandom r = new SecureRandom();
                user = "tg" + Long.toHexString(r.nextLong() & Long.MAX_VALUE);
                pass = Long.toHexString(r.nextLong() & Long.MAX_VALUE) + Long.toHexString(r.nextLong() & Long.MAX_VALUE);
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName(HOST), 0), 128);
                server = s;
                port = s.getLocalPort();
                startedAt = System.currentTimeMillis();
                Thread t = new Thread(Tgws::acceptLoop, "tgws-accept");
                t.setDaemon(true);
                t.start();
                log("listening on " + HOST + ":" + port);
                requestPick(0);
                return port;
            } catch (Throwable e) {
                FileLog.e(e);
                server = null;
                port = 0;
                return 0;
            }
        }
    }

    private static void loadState() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        try {
            String saved = prefs().getString(PREFS_KEY_DOMAINS, "");
            for (String d : saved.split("\n")) {
                d = d.trim();
                if (validDomain(d)) {
                    set.add(d);
                }
            }
            String learned = prefs().getString(PREFS_KEY_LEARNED, "");
            for (String line : learned.split("\n")) {
                String[] p = line.trim().split(" ");
                if (p.length == 2) {
                    try {
                        int v = Integer.parseInt(p[1]);
                        ipToDc.put(p[0], new Dc(Math.abs(v), v < 0));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        Collections.addAll(set, DEFAULT_DOMAINS);
        set.add(OFFICIAL);
        domains = new ArrayList<>(set);
    }

    private static boolean validDomain(String d) {
        return d != null && d.length() > 3 && d.length() < 200 && d.contains(".") && d.matches("[a-z0-9.-]+");
    }

    /** Смена сети: у другого оператора открыто и закрыто другое — подбираем заново. */
    public static void onNetworkChanged() {
        if (server == null) {
            return;
        }
        // Широковещательное «сеть изменилась» приходит и сразу после регистрации — первый
        // подбор и так уже идёт.
        if (System.currentTimeMillis() - startedAt < 15000) {
            return;
        }
        directOk.clear();
        cooldown.clear();
        requestPick(2000);
    }

    private static void requestPick(long delayMs) {
        pickPending = true;
        pool.execute(() -> {
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException ignore) {
            }
            while (pickPending && picking.compareAndSet(false, true)) {
                pickPending = false;
                try {
                    pick();
                } catch (Throwable e) {
                    FileLog.e(e);
                } finally {
                    picking.set(false);
                }
            }
        });
    }

    // ---- SOCKS5 --------------------------------------------------------------------------

    private static void acceptLoop() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try {
                Socket c = s.accept();
                pool.execute(() -> handle(c));
            } catch (IOException e) {
                if (s.isClosed()) {
                    break;
                }
            }
        }
    }

    private static void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(20000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Приветствие: принимаем только вход по логину/паролю — без них мостом мог бы
            // пользоваться кто угодно на телефоне.
            if (in.read() != 5) {
                throw new IOException("not socks5");
            }
            int n = readByte(in);
            byte[] methods = readN(in, n);
            boolean hasAuth = false;
            for (byte m : methods) {
                if (m == 2) {
                    hasAuth = true;
                    break;
                }
            }
            if (!hasAuth) {
                out.write(new byte[]{5, (byte) 0xff});
                throw new IOException("no auth method");
            }
            out.write(new byte[]{5, 2});
            out.flush();
            if (readByte(in) != 1) {
                throw new IOException("bad auth version");
            }
            String u = new String(readN(in, readByte(in)), "UTF-8");
            String p = new String(readN(in, readByte(in)), "UTF-8");
            if (!u.equals(user) || !p.equals(pass)) {
                out.write(new byte[]{1, 1});
                throw new IOException("bad credentials");
            }
            out.write(new byte[]{1, 0});
            out.flush();

            // Запрос CONNECT
            if (readByte(in) != 5) {
                throw new IOException("bad request");
            }
            int cmd = readByte(in);
            readByte(in);
            int atyp = readByte(in);
            String host;
            if (atyp == 1) {
                host = InetAddress.getByAddress(readN(in, 4)).getHostAddress();
            } else if (atyp == 4) {
                host = InetAddress.getByAddress(readN(in, 16)).getHostAddress();
            } else if (atyp == 3) {
                host = new String(readN(in, readByte(in)), "UTF-8");
            } else {
                throw new IOException("bad atyp");
            }
            int dstPort = (readByte(in) << 8) | readByte(in);
            if (cmd != 1) {
                out.write(new byte[]{5, 7, 0, 1, 0, 0, 0, 0, 0, 0});
                throw new IOException("only CONNECT");
            }
            // Успех отвечаем сразу: решение «куда» принимается по первым 64 байтам потока,
            // а клиент пришлёт их только после ответа.
            out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();

            byte[] init = readN(in, TgwsMtproto.INIT_LEN);
            route(client, in, out, host, dstPort, init);
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("tgws: " + e);
            }
        } finally {
            TgwsWebSocket.closeQuietly(client);
        }
    }

    private static void route(Socket client, InputStream in, OutputStream out, String host, int dstPort, byte[] init) throws Exception {
        TgwsMtproto.Info info = TgwsMtproto.parse(init);
        Dc dc = null;
        if (info != null) {
            // Сначала адрес: без секрета прокси tgnet номер ДЦ в заголовок не пишет, и там
            // случайные байты, которые изредка «похожи» на номер.
            dc = lookupDc(host);
            if (dc == null && info.dc != 0) {
                dc = new Dc(info.dc, info.media);
            }
        }
        if (info == null || dc == null) {
            // Не MTProto или неизвестный адрес (например, CDN) — как есть.
            relayDirect(client, in, out, host, dstPort, init, 0);
            return;
        }
        int wsDc = dc.id == 203 ? 2 : dc.id;
        // Карта стареет: открытое у оператора закрывается и наоборот. Пере-подбор в фоне,
        // не чаще раза в полчаса и только пока Telegram действительно ходит в сеть.
        if (lastPick != 0 && System.currentTimeMillis() - lastPick > 30 * 60 * 1000L && !picking.get()) {
            lastPick = System.currentTimeMillis();
            requestPick(0);
        }

        Long ok = directOk.get(wsDc);
        if (ok != null && ok > System.currentTimeMillis()) {
            relayDirect(client, in, out, host, dstPort, init, wsDc);
            return;
        }

        byte[] patched = TgwsMtproto.patchDc(init, wsDc, dc.media);
        int attempts = 0;
        for (String domain : candidates(wsDc)) {
            if (attempts >= 4) {
                break; // дольше держать соединение незачем: tgnet переподключится сам
            }
            String h = wsHost(domain, wsDc, dc.media);
            Long cd = cooldown.get(h);
            if (cd != null && cd > System.currentTimeMillis()) {
                continue;
            }
            TgwsWebSocket ws;
            attempts++;
            try {
                ws = TgwsWebSocket.connect(h, WS_TIMEOUT);
            } catch (IOException e) {
                cooldown.put(h, System.currentTimeMillis() + FAIL_COOLDOWN_MS);
                log("ws " + h + " failed: " + e.getMessage());
                continue;
            }
            cooldown.remove(h);
            relayWs(client, in, out, ws, patched, info.proto);
            return;
        }
        // Не ответил никто — пусть хотя бы попробует как есть.
        log("dc" + wsDc + ": no websocket route, going direct");
        relayDirect(client, in, out, host, dstPort, init, 0);
    }

    private static List<String> candidates(int dc) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        List<String> best = perDc.get(dc);
        if (best != null) {
            set.addAll(best);
        }
        set.addAll(domains);
        return new ArrayList<>(set);
    }

    private static String wsHost(String domain, int dc, boolean media) {
        if (OFFICIAL.equals(domain)) {
            return "kws" + dc + (media ? "-1" : "") + "." + OFFICIAL;
        }
        return "kws" + dc + "." + domain;
    }

    // ---- перекладка байтов ----------------------------------------------------------------

    private static void relayWs(Socket client, InputStream in, OutputStream out, TgwsWebSocket ws, byte[] init, int proto) throws Exception {
        client.setSoTimeout(0);
        ws.sendBinary(init, 0, init.length);
        TgwsMtproto.Splitter splitter = new TgwsMtproto.Splitter(init, proto);
        Future<?> down = pool.submit(() -> {
            try {
                while (true) {
                    byte[] m = ws.receive();
                    if (m == null) {
                        break;
                    }
                    out.write(m);
                    out.flush();
                }
            } catch (Throwable ignore) {
            } finally {
                ws.close();
                TgwsWebSocket.closeQuietly(client);
            }
        });
        byte[] buf = new byte[65536];
        try {
            while (true) {
                int r = in.read(buf);
                if (r < 0) {
                    break;
                }
                splitter.feed(buf, 0, r, ws::sendBinary);
            }
        } catch (Throwable ignore) {
        } finally {
            ws.close();
            TgwsWebSocket.closeQuietly(client);
            down.cancel(true);
        }
    }

    /**
     * Напрямую. dc != 0 — это ДЦ, который подбор признал открытым: если он промолчит, он
     * больше не считается открытым, и следующее соединение пойдёт через веб-сокет.
     */
    private static void relayDirect(Socket client, InputStream in, OutputStream out, String host, int dstPort, byte[] init, int dc) throws Exception {
        Socket up = new Socket();
        try {
            up.setTcpNoDelay(true);
            up.connect(new InetSocketAddress(InetAddress.getByName(host), dstPort), CONNECT_TIMEOUT);
        } catch (IOException e) {
            TgwsWebSocket.closeQuietly(up);
            if (dc != 0) {
                directOk.remove(dc);
            }
            throw e;
        }
        client.setSoTimeout(0);
        InputStream uin = up.getInputStream();
        OutputStream uout = up.getOutputStream();
        uout.write(init);
        uout.flush();
        AtomicBoolean answered = new AtomicBoolean(dc == 0);
        if (dc != 0) {
            up.setSoTimeout(DIRECT_ANSWER_TIMEOUT);
        }
        Future<?> down = pool.submit(() -> {
            byte[] buf = new byte[65536];
            try {
                while (true) {
                    int r;
                    try {
                        r = uin.read(buf);
                    } catch (SocketTimeoutException e) {
                        if (!answered.get()) {
                            log("dc" + dc + ": direct path silent, switching to websocket");
                            directOk.remove(dc);
                            break;
                        }
                        continue;
                    }
                    if (r < 0) {
                        break;
                    }
                    if (!answered.get()) {
                        answered.set(true);
                        up.setSoTimeout(0);
                    }
                    out.write(buf, 0, r);
                    out.flush();
                }
            } catch (Throwable ignore) {
            } finally {
                TgwsWebSocket.closeQuietly(up);
                TgwsWebSocket.closeQuietly(client);
            }
        });
        byte[] buf = new byte[65536];
        try {
            while (true) {
                int r = in.read(buf);
                if (r < 0) {
                    break;
                }
                uout.write(buf, 0, r);
                uout.flush();
            }
        } catch (Throwable ignore) {
        } finally {
            TgwsWebSocket.closeQuietly(up);
            TgwsWebSocket.closeQuietly(client);
            down.cancel(true);
        }
    }

    // ---- подбор --------------------------------------------------------------------------

    private static final class Result {
        final String domain;
        final int dc;
        final boolean ok;
        final long ms;

        Result(String domain, int dc, boolean ok, long ms) {
            this.domain = domain;
            this.dc = dc;
            this.ok = ok;
            this.ms = ms;
        }
    }

    private static void pick() throws Exception {
        long started = System.currentTimeMillis();
        status = "подбор…";
        refreshDomainList();

        // Фаза 0: какие ДЦ отвечают напрямую.
        List<Future<Result>> fs = new ArrayList<>();
        for (int dc = 1; dc <= 5; dc++) {
            final int d = dc;
            fs.add(pool.submit(() -> probeDirect(d)));
        }
        int directCount = 0;
        for (Future<Result> f : fs) {
            Result r = get(f);
            if (r != null && r.ok) {
                directOk.put(r.dc, System.currentTimeMillis() + DIRECT_COOLDOWN_MS * 6);
                directCount++;
            } else if (r != null) {
                directOk.remove(r.dc);
            }
        }
        log("direct: " + directCount + " of 5");

        // Фаза 1: отсев — одна проба на домен по ДЦ2.
        List<String> all = new ArrayList<>(domains);
        List<Result> p1 = runProbes(all, new int[]{2}, 3500, 8);
        List<Result> alive = new ArrayList<>();
        for (Result r : p1) {
            if (r.ok) {
                alive.add(r);
            }
        }
        Collections.sort(alive, (a, b) -> Long.compare(a.ms, b.ms));
        List<String> cand = new ArrayList<>();
        for (Result r : alive) {
            if (cand.size() >= 6) {
                break;
            }
            cand.add(r.domain);
        }
        for (String d : all) {
            if (cand.size() >= 3) {
                break;
            }
            if (!cand.contains(d)) {
                cand.add(d);
            }
        }
        log("alive domains: " + alive.size() + " of " + all.size());

        // Фаза 2: карта — кандидаты по всем ДЦ.
        List<Result> p2 = runProbes(cand, new int[]{1, 2, 3, 4, 5}, 6000, 8);
        Map<String, Integer> okCount = new HashMap<>();
        Map<String, Long> okMs = new HashMap<>();
        for (int dc = 1; dc <= 5; dc++) {
            List<Result> rs = new ArrayList<>();
            for (Result r : p2) {
                if (r.dc == dc && r.ok) {
                    rs.add(r);
                }
            }
            Collections.sort(rs, (a, b) -> Long.compare(a.ms, b.ms));
            List<String> list = new ArrayList<>();
            for (Result r : rs) {
                list.add(r.domain);
            }
            perDc.put(dc, list);
        }
        for (Result r : p2) {
            if (r.ok) {
                Integer c = okCount.get(r.domain);
                okCount.put(r.domain, c == null ? 1 : c + 1);
                Long m = okMs.get(r.domain);
                okMs.put(r.domain, m == null ? r.ms : m + r.ms);
            }
        }
        // Порядок пула: больше ответивших точек — выше, среди равных — быстрее; затем прошедшие
        // отсев; затем остальные (они могут ожить).
        List<String> order = new ArrayList<>(okCount.keySet());
        Collections.sort(order, (a, b) -> {
            int c = Integer.compare(okCount.get(b), okCount.get(a));
            if (c != 0) {
                return c;
            }
            return Long.compare(okMs.get(a) / okCount.get(a), okMs.get(b) / okCount.get(b));
        });
        LinkedHashSet<String> full = new LinkedHashSet<>(order);
        for (Result r : alive) {
            full.add(r.domain);
        }
        full.addAll(all);
        if (!order.isEmpty()) {
            domains = new ArrayList<>(full);
            try {
                prefs().edit().putString(PREFS_KEY_DOMAINS, TextUtils.join("\n", domains)).apply();
            } catch (Throwable ignore) {
            }
        }
        cooldown.clear();
        lastPick = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (int dc = 1; dc <= 5; dc++) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("DC").append(dc).append(": ");
            if (directOk.containsKey(dc)) {
                sb.append("напрямую");
            } else {
                List<String> l = perDc.get(dc);
                sb.append(l == null || l.isEmpty() ? "нет пути" : l.get(0));
            }
        }
        status = sb.toString();
        log("pick done in " + (System.currentTimeMillis() - started) + " ms: " + status);
    }

    private static <T> T get(Future<T> f) {
        try {
            return f.get(30, TimeUnit.SECONDS);
        } catch (Throwable e) {
            f.cancel(true);
            return null;
        }
    }

    private static List<Result> runProbes(List<String> doms, int[] dcs, int timeout, int parallel) {
        List<Result> res = Collections.synchronizedList(new ArrayList<>());
        ExecutorService ex = Executors.newFixedThreadPool(parallel, r -> {
            Thread t = new Thread(r, "tgws-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (String d : doms) {
                for (int dc : dcs) {
                    fs.add(ex.submit(() -> res.add(probeWs(d, dc, timeout))));
                }
            }
            for (Future<?> f : fs) {
                get(f);
            }
        } finally {
            ex.shutdownNow();
        }
        return new ArrayList<>(res);
    }

    private static Result probeWs(String domain, int dc, int timeout) {
        long t0 = System.currentTimeMillis();
        TgwsWebSocket ws = null;
        try {
            ws = TgwsWebSocket.connect(wsHost(domain, dc, false), timeout);
            ws.setReadTimeout(timeout);
            TgwsMtproto.Probe p = TgwsMtproto.makeProbe(dc, false);
            ws.sendBinary(p.init, 0, p.init.length);
            ws.sendBinary(p.packet, 0, p.packet.length);
            byte[] m = ws.receive();
            boolean ok = m != null && TgwsMtproto.checkProbeAnswer(p, m, m.length);
            return new Result(domain, dc, ok, System.currentTimeMillis() - t0);
        } catch (Throwable e) {
            return new Result(domain, dc, false, System.currentTimeMillis() - t0);
        } finally {
            if (ws != null) {
                ws.close();
            }
        }
    }

    /**
     * Прямая проба: не «соединилось ли», а «ответил ли resPQ». Блокировка по адресу обычно
     * пропускает рукопожатие TCP и режет то, что после.
     */
    private static Result probeDirect(int dc) {
        long t0 = System.currentTimeMillis();
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(InetAddress.getByName(DC_MAIN_IP[dc]), 443), CONNECT_TIMEOUT);
            s.setSoTimeout(5000);
            TgwsMtproto.Probe p = TgwsMtproto.makeProbe(dc, false);
            OutputStream o = s.getOutputStream();
            byte[] both = new byte[p.init.length + p.packet.length];
            System.arraycopy(p.init, 0, both, 0, p.init.length);
            System.arraycopy(p.packet, 0, both, p.init.length, p.packet.length);
            o.write(both);
            o.flush();
            InputStream i = s.getInputStream();
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            while (acc.size() < 41 + 40) {
                int r = i.read(buf);
                if (r < 0) {
                    break;
                }
                acc.write(buf, 0, r);
                if (acc.size() >= 41) {
                    break;
                }
            }
            byte[] a = acc.toByteArray();
            boolean ok = TgwsMtproto.checkProbeAnswer(p, a, a.length);
            return new Result(null, dc, ok, System.currentTimeMillis() - t0);
        } catch (Throwable e) {
            return new Result(null, dc, false, System.currentTimeMillis() - t0);
        } finally {
            TgwsWebSocket.closeQuietly(s);
        }
    }

    /** Свежий пул с зеркала sTGWS. Не вышло — работаем со снимком. */
    private static void refreshDomainList() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(DOMAINS_URL).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() != 200) {
                return;
            }
            InputStream i = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = i.read(buf)) > 0 && b.size() < 65536) {
                b.write(buf, 0, r);
            }
            LinkedHashSet<String> set = new LinkedHashSet<>(domains);
            int added = 0;
            for (String line : b.toString("UTF-8").split("\n")) {
                String d = line.trim().toLowerCase();
                if (d.startsWith("#") || !validDomain(d)) {
                    continue;
                }
                if (set.add(d)) {
                    added++;
                }
            }
            if (added > 0) {
                domains = new ArrayList<>(set);
                log("domain list: +" + added);
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ---- мелочи --------------------------------------------------------------------------

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("eof");
        }
        return b;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) {
                throw new IOException("eof");
            }
            off += r;
        }
        return b;
    }

    private static void log(String s) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("tgws: " + s);
        }
    }
}
