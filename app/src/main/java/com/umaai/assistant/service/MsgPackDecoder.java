package com.umaai.assistant.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量 MessagePack 解码器（无第三方依赖）
 * 支持: nil/bool/int/uint/float/str/bin/array/map
 * 赛马娘服务器通信使用 MessagePack 格式
 */
public class MsgPackDecoder {

    private final byte[] data;
    private int pos;

    public MsgPackDecoder(byte[] data) {
        this.data = data;
        this.pos = 0;
    }

    public static Object decode(byte[] data) {
        if (data == null || data.length == 0) return null;
        try {
            MsgPackDecoder dec = new MsgPackDecoder(data);
            return dec.decodeValue();
        } catch (Exception e) {
            return null;
        }
    }

    private Object decodeValue() {
        if (pos >= data.length) return null;
        int b = data[pos++] & 0xFF;

        // Positive fixint (0-127)
        if (b <= 0x7F) return b;

        // Fixmap (0x80-0x8F)
        if (b >= 0x80 && b <= 0x8F) {
            int n = b & 0x0F;
            return decodeMap(n);
        }

        // Fixarray (0x90-0x9F)
        if (b >= 0x90 && b <= 0x9F) {
            int n = b & 0x0F;
            return decodeArray(n);
        }

        // Fixstr (0xA0-0xBF)
        if (b >= 0xA0 && b <= 0xBF) {
            int len = b & 0x1F;
            return decodeStr(len);
        }

        // Negative fixint (0xE0-0xFF)
        if (b >= 0xE0) {
            return (byte) (b - 0x100);
        }

        switch (b) {
            case 0xC0: return null;           // nil
            case 0xC2: return false;          // false
            case 0xC3: return true;           // true
            case 0xC4: return decodeBin(read8());   // bin8
            case 0xC5: return decodeBin(read16());  // bin16
            case 0xC6: return decodeBin(read32());  // bin32
            case 0xCA: return Float.intBitsToFloat(read32()); // float32
            case 0xCB: return Double.longBitsToDouble(read64()); // float64
            case 0xCC: return read8() & 0xFF;   // uint8
            case 0xCD: return read16() & 0xFFFF; // uint16
            case 0xCE: return read32() & 0xFFFFFFFFL; // uint32
            case 0xCF: return read64();          // uint64
            case 0xD0: return (byte) read8();    // int8
            case 0xD1: return (short) read16();  // int16
            case 0xD2: return read32();          // int32
            case 0xD3: return read64();          // int64
            case 0xD9: return decodeStr(read8());    // str8
            case 0xDA: return decodeStr(read16());   // str16
            case 0xDB: return decodeStr(read32());   // str32
            case 0xDC: return decodeArray(read16()); // array16
            case 0xDD: return decodeArray(read32()); // array32
            case 0xDE: return decodeMap(read16());   // map16
            case 0xDF: return decodeMap(read32());   // map32
            default:
                return "?(0x" + Integer.toHexString(b) + ")";
        }
    }

    private int read8() {
        if (pos >= data.length) return 0;
        return data[pos++] & 0xFF;
    }

    private int read16() {
        if (pos + 1 >= data.length) return 0;
        int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
        pos += 2;
        return v;
    }

    private int read32() {
        if (pos + 3 >= data.length) return 0;
        int v = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    private long read64() {
        if (pos + 7 >= data.length) return 0;
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (data[pos + i] & 0xFF);
        }
        pos += 8;
        return v;
    }

    private String decodeStr(int len) {
        if (pos + len > data.length) len = data.length - pos;
        if (len <= 0) return "";
        String s = new String(data, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    private String decodeBin(int len) {
        if (pos + len > data.length) len = data.length - pos;
        if (len <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(len, 64); i++) {
            sb.append(String.format("%02x", data[pos + i] & 0xFF));
        }
        if (len > 64) sb.append("...");
        pos += len;
        return "bin[" + len + "]:" + sb;
    }

    private List<Object> decodeArray(int n) {
        List<Object> list = new ArrayList<>(Math.min(n, 100));
        for (int i = 0; i < n; i++) {
            list.add(decodeValue());
        }
        return list;
    }

    private Map<String, Object> decodeMap(int n) {
        Map<String, Object> map = new HashMap<>(Math.min(n, 100));
        for (int i = 0; i < n; i++) {
            Object key = decodeValue();
            Object val = decodeValue();
            String k = (key != null) ? key.toString() : "null";
            map.put(k, val);
        }
        return map;
    }

    /**
     * 将解码后的 MessagePack 值格式化为紧凑文本
     */
    @SuppressWarnings("unchecked")
    public static String formatValue(Object val, int depth) {
        if (val == null) return "null";
        if (val instanceof Boolean) return val.toString();
        if (val instanceof Number) {
            Number n = (Number) val;
            long lv = n.longValue();
            if (n.doubleValue() == lv && Math.abs(n.doubleValue()) < 1e15) {
                return String.valueOf(lv);
            }
            return String.valueOf(n.doubleValue());
        }
        if (val instanceof String) return (String) val;
        if (val instanceof List) {
            List<Object> list = (List<Object>) val;
            if (list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(formatValue(list.get(i), depth + 1));
            }
            return "[" + sb + "]";
        }
        if (val instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) val;
            if (map.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            String indent = (depth > 0) ? "" : "";
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(indent).append(e.getKey()).append("=")
                  .append(formatValue(e.getValue(), depth + 1));
            }
            return "{" + sb + "}";
        }
        return val.toString();
    }

    /**
     * 格式化为多行树形文本（适合悬浮窗显示）
     */
    @SuppressWarnings("unchecked")
    public static String formatTree(Object val, String indent) {
        if (val == null) return indent + "null";
        if (val instanceof Boolean) return indent + val;
        if (val instanceof Number) return indent + val;
        if (val instanceof String) return indent + "\"" + val + "\"";
        if (val instanceof List) {
            List<Object> list = (List<Object>) val;
            if (list.isEmpty()) return indent + "[]";
            StringBuilder sb = new StringBuilder(indent + "[\n");
            for (int i = 0; i < list.size(); i++) {
                sb.append(formatTree(list.get(i), indent + "  "));
                if (i < list.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append(indent).append("]");
            return sb.toString();
        }
        if (val instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) val;
            if (map.isEmpty()) return indent + "{}";
            StringBuilder sb = new StringBuilder(indent + "{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                sb.append(indent).append("  ").append(e.getKey()).append(": ");
                Object v = e.getValue();
                if (v instanceof Map || v instanceof List) {
                    sb.append("\n").append(formatTree(v, indent + "    "));
                } else {
                    sb.append(formatValue(v, 0));
                }
                if (i < map.size() - 1) sb.append(",");
                sb.append("\n");
                i++;
            }
            sb.append(indent).append("}");
            return sb.toString();
        }
        return indent + val;
    }
}
