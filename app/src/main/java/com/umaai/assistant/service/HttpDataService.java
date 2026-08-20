package com.umaai.assistant.service;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public final class HttpDataService extends NanoHTTPD {
    public static final int PORT = 18766;
    public interface OnDataListener { void onDataReceived(String data); }
    private final OnDataListener listener;

    public HttpDataService(OnDataListener listener) {
        super("127.0.0.1", PORT);
        this.listener = listener;
    }

    public void startServer() throws IOException { start(SOCKET_READ_TIMEOUT, false); }
    public void stopServer() { stop(); }

    @Override public Response serve(IHTTPSession session) {
        try {
            if ("/status".equals(session.getUri()) || "/".equals(session.getUri())) {
                JSONObject value = new JSONObject();
                value.put("app", "uma-juece-ramen");
                value.put("scenario", "Ramen");
                value.put("http_port", PORT);
                value.put("status", "running");
                return json(value.toString());
            }
            if ("/data".equals(session.getUri()) && session.getMethod() == Method.POST) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String body = files.get("postData");
                if (body != null && listener != null) listener.onDataReceived(body);
                return json("{\"ok\":true}");
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Use POST /data or GET /status");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response json(String body) {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body);
    }
}
