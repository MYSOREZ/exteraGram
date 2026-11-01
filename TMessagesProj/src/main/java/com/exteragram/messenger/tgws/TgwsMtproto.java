package com.exteragram.messenger.tgws;

import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Всё, что мосту нужно знать про транспорт MTProto: разбор и правка первых 64 байт
 * (obfuscated2), нарезка потока на пакеты и проба «ответил ли дата-центр».
 *
 * Мост поток НЕ расшифровывает и не перешифровывает: клиент и дата-центр говорят друг с
 * другом напрямую, а мост лишь перекладывает байты. Ключи из заголовка нужны только чтобы
 * знать границы пакетов — точка kwsN ждёт каждый пакет отдельным кадром веб-сокета.
 */
final class TgwsMtproto {

    static final int INIT_LEN = 64;
    static final int PROTO_ABRIDGED = 0xefefefef;
    static final int PROTO_INTERMEDIATE = 0xeeeeeeee;
    static final int PROTO_PADDED = 0xdddddddd;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TgwsMtproto() {
    }

    static Cipher ctr(byte[] key, int keyOff, byte[] iv, int ivOff) throws Exception {
        Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
        byte[] k = new byte[32];
        byte[] v = new byte[16];
        System.arraycopy(key, keyOff, k, 0, 32);
        System.arraycopy(iv, ivOff, v, 0, 16);
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(v));
        return c;
    }

    /** Шифр клиент→сервер, уже прокрученный на 64 байта заголовка. */
    static Cipher clientCipher(byte[] init) throws Exception {
        Cipher c = ctr(init, 8, init, 40);
        c.update(new byte[INIT_LEN]);
        return c;
    }

    private static byte[] keystream(byte[] init) throws Exception {
        return ctr(init, 8, init, 40).update(new byte[INIT_LEN]);
    }

    static final class Info {
        int proto;
        int dc;          // 0 — в заголовке не записан
        boolean media;
    }

    /** null — это не obfuscated2 (значит, не трогаем и пропускаем как есть). */
    static Info parse(byte[] init) {
        try {
            byte[] ks = keystream(init);
            byte[] p = new byte[8];
            for (int i = 0; i < 8; i++) {
                p[i] = (byte) (init[56 + i] ^ ks[56 + i]);
            }
            int proto = (p[0] & 0xff) | (p[1] & 0xff) << 8 | (p[2] & 0xff) << 16 | (p[3] & 0xff) << 24;
            if (proto != PROTO_ABRIDGED && proto != PROTO_INTERMEDIATE && proto != PROTO_PADDED) {
                return null;
            }
            Info info = new Info();
            info.proto = proto;
            short raw = (short) ((p[4] & 0xff) | (p[5] & 0xff) << 8);
            int dc = Math.abs(raw);
            // Без секрета прокси tgnet байты 60–61 не заполняет — там случайность. Номер
            // принимаем, только если он похож на настоящий; иначе ДЦ берётся по адресу.
            if ((dc >= 1 && dc <= 5) || dc == 203) {
                info.dc = dc;
                info.media = raw < 0;
            }
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    /** Вписывает номер ДЦ в заголовок (точке kwsN он нужен; самому ДЦ — безразличен). */
    static byte[] patchDc(byte[] init, int dc, boolean media) {
        byte[] out = init.clone();
        try {
            byte[] ks = keystream(init);
            short v = (short) (media ? -dc : dc);
            out[60] = (byte) (ks[60] ^ (v & 0xff));
            out[61] = (byte) (ks[61] ^ ((v >> 8) & 0xff));
        } catch (Exception ignore) {
        }
        return out;
    }

    /**
     * Нарезка шифрованного потока клиента на транспортные пакеты. Каждый пакет уходит в
     * веб-сокет отдельным кадром — иначе точка kwsN склеенные пакеты не принимает.
     */
    static final class Splitter {
        private final Cipher cipher;
        private final int proto;
        private byte[] enc = new byte[0];
        private byte[] plain = new byte[0];
        private boolean disabled;

        Splitter(byte[] init, int proto) throws Exception {
            this.cipher = clientCipher(init);
            this.proto = proto;
        }

        interface Out {
            void packet(byte[] b, int off, int len) throws java.io.IOException;
        }

        void feed(byte[] chunk, int off, int len, Out out) throws java.io.IOException {
            if (len <= 0) {
                return;
            }
            if (disabled) {
                out.packet(chunk, off, len);
                return;
            }
            byte[] dec = cipher.update(chunk, off, len);
            enc = concat(enc, chunk, off, len);
            plain = concat(plain, dec, 0, dec == null ? 0 : dec.length);
            int pos = 0;
            while (pos < enc.length) {
                int n = nextLen(pos);
                if (n == 0) {
                    break; // пакет ещё не дочитан
                }
                if (n < 0) {
                    // Непонятная длина — дальше режем не пакетами, а как пришло.
                    out.packet(enc, pos, enc.length - pos);
                    pos = enc.length;
                    disabled = true;
                    break;
                }
                out.packet(enc, pos, n);
                pos += n;
            }
            if (pos > 0) {
                enc = tail(enc, pos);
                plain = tail(plain, pos);
            }
        }

        private int nextLen(int pos) {
            int avail = plain.length - pos;
            if (avail <= 0) {
                return 0;
            }
            if (proto == PROTO_ABRIDGED) {
                int first = plain[pos] & 0xff;
                int payload;
                int header = 1;
                if (first == 0x7f || first == 0xff) {
                    if (avail < 4) {
                        return 0;
                    }
                    payload = ((plain[pos + 1] & 0xff) | (plain[pos + 2] & 0xff) << 8 | (plain[pos + 3] & 0xff) << 16) * 4;
                    header = 4;
                } else {
                    payload = (first & 0x7f) * 4;
                }
                if (payload <= 0) {
                    return -1;
                }
                return avail >= header + payload ? header + payload : 0;
            }
            if (avail < 4) {
                return 0;
            }
            int payload = ((plain[pos] & 0xff) | (plain[pos + 1] & 0xff) << 8 | (plain[pos + 2] & 0xff) << 16 | (plain[pos + 3] & 0x7f) << 24);
            if (payload <= 0 || payload > 16 * 1024 * 1024) {
                return -1;
            }
            return avail >= 4 + payload ? 4 + payload : 0;
        }
    }

    private static byte[] concat(byte[] a, byte[] b, int off, int len) {
        byte[] r = new byte[a.length + len];
        System.arraycopy(a, 0, r, 0, a.length);
        if (len > 0) {
            System.arraycopy(b, off, r, a.length, len);
        }
        return r;
    }

    private static byte[] tail(byte[] a, int from) {
        byte[] r = new byte[a.length - from];
        System.arraycopy(a, from, r, 0, r.length);
        return r;
    }

    // ---- проба: req_pq_multi → resPQ -------------------------------------------------------

    /** Готовая проба: заголовок, первый пакет и расшифровщик ответа. */
    static final class Probe {
        byte[] init;
        byte[] packet;
        Cipher decrypt;
        byte[] nonce = new byte[16];
    }

    static Probe makeProbe(int dc, boolean media) throws Exception {
        byte[] b = new byte[INIT_LEN];
        while (true) {
            RANDOM.nextBytes(b);
            int v1 = (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
            int v2 = (b[4] & 0xff) | (b[5] & 0xff) << 8 | (b[6] & 0xff) << 16 | (b[7] & 0xff) << 24;
            if ((b[0] & 0xff) != 0xef && v1 != 0x44414548 && v1 != 0x54534f50 && v1 != 0x20544547
                    && v1 != 0x4954504f && v1 != 0xeeeeeeee && v1 != 0xdddddddd && v1 != 0x02010316 && v2 != 0) {
                break;
            }
        }
        b[56] = b[57] = b[58] = b[59] = (byte) 0xef;
        short d = (short) (media ? -dc : dc);
        b[60] = (byte) (d & 0xff);
        b[61] = (byte) ((d >> 8) & 0xff);

        byte[] rev = new byte[48];
        for (int i = 0; i < 48; i++) {
            rev[i] = b[55 - i];
        }
        Cipher enc = ctr(b, 8, b, 40);
        Cipher dec = ctr(rev, 0, rev, 32);
        byte[] encrypted = enc.update(b);
        Probe p = new Probe();
        p.init = b.clone();
        System.arraycopy(encrypted, 56, p.init, 56, 8);

        RANDOM.nextBytes(p.nonce);
        byte[] payload = new byte[40];
        // auth_key_id = 0
        long msgId = (System.currentTimeMillis() / 1000L) << 32 | ((long) RANDOM.nextInt() & 0xfffffffcL);
        putLong(payload, 8, msgId);
        putInt(payload, 16, 20);
        putInt(payload, 20, 0xbe7e8ef1); // req_pq_multi
        System.arraycopy(p.nonce, 0, payload, 24, 16);
        byte[] plainPacket = new byte[41];
        plainPacket[0] = (byte) (payload.length / 4);
        System.arraycopy(payload, 0, plainPacket, 1, payload.length);
        p.packet = enc.update(plainPacket);
        p.decrypt = dec;
        return p;
    }

    /** Ответ на пробу — это resPQ с нашим nonce. Проверяем ровно это, а не «что-то пришло». */
    static boolean checkProbeAnswer(Probe p, byte[] data, int len) {
        if (len < 1 + 40) {
            return false;
        }
        byte[] d = p.decrypt.update(data, 0, len);
        int off = 1;
        if ((d[0] & 0xff) == 0x7f) {
            off = 4;
        }
        if (d.length < off + 40) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            if (d[off + i] != 0) {
                return false;
            }
        }
        int ctor = (d[off + 20] & 0xff) | (d[off + 21] & 0xff) << 8 | (d[off + 22] & 0xff) << 16 | (d[off + 23] & 0xff) << 24;
        if (ctor != 0x05162463) {
            return false;
        }
        for (int i = 0; i < 16; i++) {
            if (d[off + 24 + i] != p.nonce[i]) {
                return false;
            }
        }
        return true;
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putLong(byte[] b, int off, long v) {
        putInt(b, off, (int) v);
        putInt(b, off + 4, (int) (v >> 32));
    }
}
