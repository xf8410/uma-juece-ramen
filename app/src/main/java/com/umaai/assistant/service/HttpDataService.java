package com.umaai.assistant.service;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import fi.iki.elonen.NanoHTTPD;

public final class HttpDataService extends NanoHTTPD {
    public static final int PORT = 18766;
    public interface OnDataListener { JSONObject onDataReceived(String data); }
    private final OnDataListener listener;
    private final Supplier<JSONObject> status;

    public HttpDataService(OnDataListener listener) {
        this(listener, JSONObject::new);
    }
    public HttpDataService(OnDataListener listener, Supplier<JSONObject> status) {
        this(PORT,listener,status);
    }
    HttpDataService(int port,OnDataListener listener,Supplier<JSONObject> status) {
        super("127.0.0.1", port);
        this.listener = listener;
        this.status = status;
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
                value.put("runtime", status.get());
                value.put("snapshot_schema_version", 1);
                value.put("supported_snapshot_schema_versions",new org.json.JSONArray().put(1).put(2));
                value.put("ack_semantics","transport_received_then_async_runtime_validation");
                return json(value.toString());
            }
            if ("/data".equals(session.getUri()) && session.getMethod() == Method.POST) {
                long length;
                try { length = Long.parseLong(session.getHeaders().getOrDefault("content-length", "-1")); }
                catch (NumberFormatException error) { length = -1; }
                if (length < 1 || length > PrivateFiles.MAX_SNAPSHOT_BYTES)
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"Content-Length must be 1..4194304\"}");
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String body = files.get("postData");
                if (body == null || body.length() > PrivateFiles.MAX_SNAPSHOT_BYTES)
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"error\":\"missing JSON body\"}");
                new JSONObject(body);
                JSONObject ack=listener==null?new JSONObject().put("status","rejected").put("error","receiver_unavailable"):listener.onDataReceived(body);
                return newFixedLengthResponse("rejected".equals(ack.optString("status"))?Response.Status.BAD_REQUEST:Response.Status.ACCEPTED,
                    "application/json; charset=utf-8",ack.toString());
            }
            // 决策日志拉取：adb forward tcp:18766 tcp:18766 && curl http://127.0.0.1:18766/decision_log
            if ("/decision_log".equals(session.getUri()) && session.getMethod() == Method.GET) {
                String body = RamenDecisionLogger.readLog();
                return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", body);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain",
                    "Use POST /data, GET /status or GET /decision_log");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response json(String body) {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body);
    }
}
