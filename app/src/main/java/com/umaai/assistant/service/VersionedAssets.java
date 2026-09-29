package com.umaai.assistant.service;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;

/** Verify the bundled manifest and every file before activating a data version. */
public final class VersionedAssets {
    private VersionedAssets() {}
    public static synchronized File install(Context context) throws Exception {
        return install(context,null);
    }
    public static synchronized File install(Context context,String expectedEngineRevision) throws Exception {
        File root = new File(context.getFilesDir(), "data");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建数据目录");
        try(RandomAccessFile guard=new RandomAccessFile(new File(root,".install.lock"),"rw");
            java.nio.channels.FileLock lock=guard.getChannel().lock()) {
            return installLocked(context,root,expectedEngineRevision);
        }
    }
    private static File installLocked(Context context,File root,String expectedEngineRevision) throws Exception {
        String raw;
        try (InputStream in = context.getAssets().open("gamedata/manifest.json")) { raw = PrivateFiles.read(in, 1024 * 1024); }
        JSONObject manifest = new JSONObject(raw);
        if (manifest.getInt("schema_version") != 1) throw new IOException("不支持的数据清单版本");
        if (manifest.getString("engine_revision").isEmpty() || manifest.getString("config_version").isEmpty())
            throw new IOException("数据清单缺少引擎或配置版本");
        if(expectedEngineRevision!=null&&!expectedEngineRevision.equals(manifest.getString("engine_revision")))
            throw new IOException("游戏数据与当前原生引擎版本不一致，拒绝激活");
        String version = PrivateFiles.sha256(raw.getBytes(StandardCharsets.UTF_8));
        File directory = new File(root, version);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建数据目录");
        JSONObject files = manifest.getJSONObject("files");
        String[] required = {"constants.json", "cardDB.json", "umaDB.json", "text_data_dict.json", "events.json", "scenario_ramen.json", "default_config.toml"};
        for (String name : required) if (!files.has(name)) throw new IOException("数据清单缺少 " + name);
        Iterator<String> names = files.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) throw new IOException("数据文件名不合法");
            String expected = files.getString(name);
            if (!expected.matches("[a-fA-F0-9]{64}")) throw new IOException("校验和不合法：" + name);
            File target = new File(directory, name);
            if (target.isFile() && expected.equalsIgnoreCase(PrivateFiles.sha256(target))) continue;
            File temporary = new File(directory, name + ".partial");
            try (InputStream in = context.getAssets().open("gamedata/" + name); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            if (!expected.equalsIgnoreCase(PrivateFiles.sha256(temporary))) throw new IOException("数据校验失败：" + name);
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        PrivateFiles.write(new File(directory, "manifest.json"), raw);
        File active = new File(root, "current.json");
        JSONObject pointer = new JSONObject().put("version", version).put("engine_revision", manifest.getString("engine_revision"));
        if (active.isFile()) {
            JSONObject old = new JSONObject(PrivateFiles.read(active, 65536));
            if (!version.equals(old.optString("version"))) pointer.put("previous", old.optString("version"));
            else if (old.has("previous")) pointer.put("previous", old.getString("previous"));
        }
        PrivateFiles.write(active, pointer.toString());
        return directory;
    }
}
