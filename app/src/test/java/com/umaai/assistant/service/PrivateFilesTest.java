package com.umaai.assistant.service;
import java.io.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public class PrivateFilesTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void writesUtf8AndReplacesAtomically() throws Exception {File f=new File(temp.getRoot(),"nested/a.json");PrivateFiles.write(f,"初始");PrivateFiles.write(f,"新版");assertEquals("新版",PrivateFiles.read(f,100));}
    @Test(expected=IOException.class)public void refusesParentTraversal()throws Exception{PrivateFiles.within(temp.getRoot(),new File(temp.getRoot(),"../escape").getPath());}
    @Test(expected=IOException.class)public void limitsLargeInputs()throws Exception{PrivateFiles.read(new ByteArrayInputStream(new byte[1024]),100);}
}
