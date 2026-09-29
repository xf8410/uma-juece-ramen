package com.umaai.assistant.service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** Small, atomic files shared by the UI and the private engine process. */
public final class PrivateFiles {
    public static final int MAX_SNAPSHOT_BYTES = 4 * 1024 * 1024;
    private PrivateFiles() {}
    public static String read(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return read(in, limit); }
    }
    public static String read(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > limit) throw new IOException("文件超过允许大小");
            out.write(buffer, 0, count);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
    public static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建 " + parent);
        File temporary = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch(java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    public static File within(File root, String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        String prefix = root.getCanonicalPath() + File.separator;
        if (!file.getPath().startsWith(prefix)) throw new IOException("文件不在应用私有目录");
        return file;
    }
    public static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
