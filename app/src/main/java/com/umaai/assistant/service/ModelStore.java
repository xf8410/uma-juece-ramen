package com.umaai.assistant.service;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Imports a local model ZIP; activation happens only after manifest and file verification. */
public final class ModelStore {
    private ModelStore() {}
    public static File install(InputStream source, File root, String engineRevision) throws Exception {
        File staging = new File(root, "staging-" + UUID.randomUUID());
        if (!staging.mkdirs()) throw new IOException("无法创建模型目录");
        long total = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 100) throw new IOException("模型包文件数超过上限");
                String name = entry.getName();
                if (name.contains("\\") || name.startsWith("/") || name.contains("..")) throw new IOException("模型包路径不合法");
                File file = PrivateFiles.within(staging, new File(staging,name).getPath());
                if (entry.isDirectory()) { if(!file.isDirectory()&&!file.mkdirs())throw new IOException("无法创建模型目录");continue; }
                if (file.exists()) throw new IOException("模型包包含重复文件");
                if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new IOException("无法创建模型目录");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = zip.read(buffer)) != -1) {
                        total += n; if (total > 1024L * 1024 * 1024) throw new IOException("模型包解压后超过 1 GB");
                        out.write(buffer,0,n);
                    }
                    out.getFD().sync();
                }
            }
        }
        File model = validate(staging, engineRevision);
        String manifest = PrivateFiles.read(new File(staging,"manifest.json"),1024*1024);
        String version = PrivateFiles.sha256(manifest.getBytes(StandardCharsets.UTF_8));
        File target = new File(root,version);
        // Versioned destination is immutable. A corrupt previous copy must never be silently selected.
        if (target.exists()) { validate(target,engineRevision); }
        else Files.move(staging.toPath(),target.toPath());
        String relative = staging.toPath().relativize(model.toPath()).toString();
        File pointer = new File(root,"current.json");
        JSONObject current = new JSONObject().put("version",version).put("model_path",new File(target,relative).getAbsolutePath());
        if(pointer.isFile())current.put("previous",new JSONObject(PrivateFiles.read(pointer,65536)).optString("version"));
        PrivateFiles.write(pointer,current.toString());
        return new File(target,relative);
    }
    public static File validate(File directory,String engineRevision) throws Exception {
        JSONObject manifest = new JSONObject(PrivateFiles.read(new File(directory,"manifest.json"),1024*1024));
        if(manifest.getInt("schema_version")!=1)throw new IOException("不支持的模型清单版本");
        if(!engineRevision.equals(manifest.getString("engine_revision")))throw new IOException("模型与当前引擎版本不匹配");
        JSONObject files=manifest.getJSONObject("files");Iterator<String> names=files.keys();
        while(names.hasNext()) {
            String name=names.next();File file=PrivateFiles.within(directory,new File(directory,name).getPath());
            if(!file.isFile()||!files.getString(name).equalsIgnoreCase(PrivateFiles.sha256(file)))throw new IOException("模型文件校验失败："+name);
        }
        String modelName=manifest.getString("model_file");
        if(modelName.contains("/")||modelName.contains("\\"))throw new IOException("ONNX 文件必须位于模型包根目录");
        if(!files.has(modelName)||!modelName.endsWith(".onnx"))throw new IOException("清单未校验 ONNX 文件");
        return PrivateFiles.within(directory,new File(directory,modelName).getPath());
    }
}
