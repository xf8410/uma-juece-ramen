package com.umaai.assistant.service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public class ModelStoreTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private byte[] pack(String revision,String hash,String extra)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)) {
            JSONObject manifest=new JSONObject().put("schema_version",1).put("engine_revision",revision).put("model_file","model.onnx")
                .put("files",new JSONObject().put("model.onnx",hash));
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("model.onnx"));zip.write(new byte[]{1,2,3});zip.closeEntry();
            if(extra!=null){zip.putNextEntry(new ZipEntry(extra));zip.write(1);zip.closeEntry();}
        }return bytes.toByteArray();
    }
    @Test public void verifiedModelActivatedOnlyAfterEveryHashMatches()throws Exception {
        File root=new File(temp.getRoot(),"models");
        File model=ModelStore.install(new ByteArrayInputStream(pack("engine1",PrivateFiles.sha256(new byte[]{1,2,3}),null)),root,"engine1");
        assertTrue(model.isFile());assertTrue(new File(root,"current.json").isFile());
    }
    @Test public void corruptImportPreservesPreviousActivePointer()throws Exception {
        File root=new File(temp.getRoot(),"models");root.mkdirs();File pointer=new File(root,"current.json");PrivateFiles.write(pointer,"{\"version\":\"previous\"}");
        try{ModelStore.install(new ByteArrayInputStream(pack("engine1","0".repeat(64),null)),root,"engine1");fail("must reject corrupt model");}catch(IOException expected){}
        assertEquals("previous",new JSONObject(PrivateFiles.read(pointer,100)).getString("version"));
    }
    @Test(expected=IOException.class)public void incompatibleEngineCannotActivateModel()throws Exception {
        ModelStore.install(new ByteArrayInputStream(pack("older",PrivateFiles.sha256(new byte[]{1,2,3}),null)),new File(temp.getRoot(),"models"),"current");
    }
    @Test(expected=IOException.class)public void zipCannotEscapeModelDirectory()throws Exception {
        ModelStore.install(new ByteArrayInputStream(pack("engine1",PrivateFiles.sha256(new byte[]{1,2,3}),"../escape")),new File(temp.getRoot(),"models"),"engine1");
    }
}
