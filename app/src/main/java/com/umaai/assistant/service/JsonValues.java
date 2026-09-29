package com.umaai.assistant.service;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
/** Android's org.json lacks JSONObject.similar. Compare structure without changing key order. */
final class JsonValues {
    static boolean same(Object a,Object b) {
        if(a==b)return true;
        if(a==null||b==null)return false;
        if(a instanceof JSONObject&&b instanceof JSONObject) {
            JSONObject left=(JSONObject)a,right=(JSONObject)b;
            if(left.length()!=right.length())return false;
            Iterator<String> keys=left.keys();while(keys.hasNext()) {String key=keys.next();if(!right.has(key)||!same(left.opt(key),right.opt(key)))return false;}return true;
        }
        if(a instanceof JSONArray&&b instanceof JSONArray) {
            JSONArray left=(JSONArray)a,right=(JSONArray)b;if(left.length()!=right.length())return false;
            for(int i=0;i<left.length();i++)if(!same(left.opt(i),right.opt(i)))return false;return true;
        }
        if(a instanceof Number&&b instanceof Number)return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()))==0;
        return a.equals(b);
    }
    private JsonValues(){}
}
