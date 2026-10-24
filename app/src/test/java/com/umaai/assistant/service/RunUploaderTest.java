package com.umaai.assistant.service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RunUploaderTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();

    @Test public void packArchivesWholeRunDirectoryAndSkipsTempFiles()throws Exception {
        File run=temp.newFolder("game7");
        File envelopes=new File(run,"envelopes");
        assertTrue(envelopes.mkdirs());
        write(new File(run,"meta.json"),"{\"complete\":true}");
        write(new File(envelopes,"10.json"),"{}");
        write(new File(run,"decisions.csv.tmp"),"partial");
        File pack=RunUploader.pack(run,new File(temp.getRoot(),"pending"),7);
        assertEquals("game7.zip",pack.getName());
        try(ZipFile zip=new ZipFile(pack)) {
            assertNotNull(zip.getEntry("meta.json"));
            assertNotNull(zip.getEntry("envelopes/10.json"));
            assertNull(zip.getEntry("decisions.csv.tmp"));
        }
    }

    @Test public void packRejectsMissingRunDirectory()throws Exception {
        try {
            RunUploader.pack(new File(temp.getRoot(),"missing"),new File(temp.getRoot(),"pending"),7);
            fail("不存在的局目录必须拒绝打包");
        } catch(IOException expected) { assertTrue(expected.getMessage().contains("局目录不存在")); }
    }

    @Test public void runIdParsesOnlyFinishedPackNames() {
        assertEquals(42,RunUploader.runIdOf("game42.zip"));
        assertEquals(-1,RunUploader.runIdOf("game42.zip.tmp"));
        assertEquals(-1,RunUploader.runIdOf("game.zip"));
        assertEquals(-1,RunUploader.runIdOf(null));
    }

    private static void write(File file,String text)throws IOException {
        Files.write(file.toPath(),text.getBytes(StandardCharsets.UTF_8));
    }
}
