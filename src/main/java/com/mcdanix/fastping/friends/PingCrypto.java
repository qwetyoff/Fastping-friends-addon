package com.mcdanix.fastping.friends;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

public class PingCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LEN = 2;
    private static final int MAC_LEN = 2;

    public static byte[] packPing(String sender, double x, double y, double z, String dimId) {
        byte dimCode = encodeDimension(dimId);
        byte header = (byte) ((4 * 16) | (dimCode & 0x0F));
        short nickHash = crc16Nick(sender);

        int ix = (int) Math.round(x * 10.0);
        short iy = (short) Math.max(-32768, Math.min(32767, Math.round(y * 10.0)));
        int iz = (int) Math.round(z * 10.0);

        ByteBuffer buf = ByteBuffer.allocate(11);
        buf.put(header);
        buf.putShort(nickHash);
        putInt24(buf, ix);
        buf.putShort(iy);
        putInt24(buf, iz);
        return buf.array();
    }

    public static byte[] packSimple(int type, String sender) {
        byte header = (byte) ((type & 0x0F) * 16);
        ByteBuffer buf = ByteBuffer.allocate(3);
        buf.put(header);
        buf.putShort(crc16Nick(sender));
        return buf.array();
    }

    public static short crc16Nick(String nick) {
        byte[] data = nick.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        int crc = 0xFFFF;
        for (int idx = 0; idx < data.length; idx++) {
            crc ^= (data[idx] & 0xFF) * 256;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc * 2) ^ 0x1021;
                } else {
                    crc = crc * 2;
                }
            }
        }
        return (short) (crc & 0xFFFF);
    }

    public static String encryptBytes(byte[] plain, String passphrase) {
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);

            byte[] key = sha256(passphrase.getBytes(StandardCharsets.UTF_8));
            byte[] stream = sha256(concat(key, iv));

            byte[] cipher = new byte[plain.length];
            for (int i = 0; i < plain.length; i++) {
                cipher[i] = (byte) (plain[i] ^ stream[i]);
            }

            byte[] macFull = sha256(concat(key, iv, cipher));
            ByteBuffer out = ByteBuffer.allocate(IV_LEN + cipher.length + MAC_LEN);
            out.put(iv);
            out.put(cipher);
            out.put(macFull, 0, MAC_LEN);

            return Base64.getUrlEncoder().withoutPadding().encodeToString(out.array());
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] decryptBytes(String base64, String passphrase) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(base64);
            if (raw.length <= IV_LEN + MAC_LEN) {
                return null;
            }
            byte[] iv = Arrays.copyOfRange(raw, 0, IV_LEN);
            byte[] cipher = Arrays.copyOfRange(raw, IV_LEN, raw.length - MAC_LEN);
            byte[] receivedMac = Arrays.copyOfRange(raw, raw.length - MAC_LEN, raw.length);

            byte[] key = sha256(passphrase.getBytes(StandardCharsets.UTF_8));
            byte[] expectedMac = Arrays.copyOf(sha256(concat(key, iv, cipher)), MAC_LEN);

            if (!MessageDigest.isEqual(receivedMac, expectedMac)) {
                return null;
            }

            byte[] stream = sha256(concat(key, iv));
            byte[] plain = new byte[cipher.length];
            for (int i = 0; i < cipher.length; i++) {
                plain[i] = (byte) (cipher[i] ^ stream[i]);
            }
            return plain;
        } catch (Exception e) {
            return null;
        }
    }

    private static void putInt24(ByteBuffer buf, int val) {
        buf.put((byte) ((val >> 16) & 0xFF));
        buf.put((byte) ((val >> 8) & 0xFF));
        buf.put((byte) (val & 0xFF));
    }

    public static int getInt24(ByteBuffer buf) {
        int b1 = buf.get() & 0xFF;
        int b2 = buf.get() & 0xFF;
        int b3 = buf.get() & 0xFF;
        int val = (b1 * 65536) | (b2 * 256) | b3;
        if ((val & 0x800000) != 0) {
            val |= 0xFF000000;
        }
        return val;
    }

    private static byte encodeDimension(String dimId) {
        if ("minecraft:the_nether".equals(dimId)) return 1;
        if ("minecraft:the_end".equals(dimId)) return 2;
        return 0;
    }

    public static String decodeDimension(int code) {
        if (code == 1) return "minecraft:the_nether";
        if (code == 2) return "minecraft:the_end";
        return "minecraft:overworld";
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (int i = 0; i < arrays.length; i++) {
            total += arrays[i].length;
        }
        byte[] res = new byte[total];
        int pos = 0;
        for (int i = 0; i < arrays.length; i++) {
            System.arraycopy(arrays[i], 0, res, pos, arrays[i].length);
            pos += arrays[i].length;
        }
        return res;
    }
}
