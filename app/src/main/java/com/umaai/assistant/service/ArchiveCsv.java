package com.umaai.assistant.service;

import java.io.*;
import java.util.*;

/** RFC4180 reader for preserving legacy decision rows during the additive identity-column migration. */
final class ArchiveCsv {
    static List<List<String>> parse(String text)throws IOException {
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder value=new StringBuilder();
        boolean quoted=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c=='"') {
                if(quoted&&i+1<text.length()&&text.charAt(i+1)=='"'){value.append('"');i++;}
                else quoted=!quoted;
            }else if(c==','&&!quoted){row.add(value.toString());value.setLength(0);}
            else if((c=='\n'||c=='\r')&&!quoted){if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;row.add(value.toString());value.setLength(0);rows.add(row);row=new ArrayList<>();}
            else value.append(c);
        }
        if(quoted)throw new IOException("旧 CSV 引号未闭合，保留原文件");
        if(value.length()>0||!row.isEmpty()){row.add(value.toString());rows.add(row);}return rows;
    }
    static String write(List<String> row){List<String> columns=new ArrayList<>();for(String v:row)columns.add(RunArchive.csv(v));return String.join(",",columns);}
}
