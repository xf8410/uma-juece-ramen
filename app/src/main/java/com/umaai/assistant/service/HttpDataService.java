package com.umaai.assistant.service;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * App端HTTP服务器，监听18766端口
 * 接收外部推送数据并转发到悬浮窗显示
 *
 * 接口：
 *   GET  /          → 状态页
 *   GET  /status    → JSON状态信息
 *   GET  /data?msg=xxx  → 推送数据到悬浮窗（方便浏览器测试）
 *   POST /data      → 推送JSON数据到悬浮窗
 *   GET  /test_board → 推送小黑板测试数据
 */
public class HttpDataService extends NanoHTTPD {

    private static final String TAG = "UmaHttp";
    public static final int PORT = 18766;

    private OnDataListener dataListener;

    public interface OnDataListener {
        void onDataReceived(String data);
    }

    public HttpDataService(OnDataListener listener) {
        super(PORT);
        this.dataListener = listener;
    }

    public void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.d(TAG, "HTTP server started on port " + PORT);
    }

    public void stopServer() {
        stop();
        Log.d(TAG, "HTTP server stopped");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        Log.d(TAG, method + " " + uri);

        try {
            switch (uri) {
                case "/":
                case "/status":
                    return handleStatus(session);

                case "/data":
                    if (method == Method.GET) {
                        return handleGetData(session);
                    } else if (method == Method.POST) {
                        return handlePostData(session);
                    }
                    return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED,
                            "text/plain", "Use GET or POST");

                case "/test_board":
                    return handleTestBoard();

                default:
                    return newFixedLengthResponse(Response.Status.NOT_FOUND,
                            "text/plain", "Not found. Try /status, /data, /test_board");
            }
        } catch (Exception e) {
            Log.e(TAG, "serve error: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain", "Error: " + e.getMessage());
        }
    }

    private Response handleStatus(IHTTPSession session) {
        JSONObject status = new JSONObject();
        try {
            status.put("app", "uma-juece");
            status.put("version", "1.24");
            status.put("mode", "blackboard");
            status.put("http_port", PORT);
            status.put("hook_port", 18765);
            status.put("status", "running");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", status.toString());
    }

    private Response handleGetData(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String msg = params.get("msg");

        if (msg == null || msg.isEmpty()) {
            String html = "<html><body>"
                    + "<h2>uma-juece 小黑板数据推送</h2>"
                    + "<p>用法: /data?msg=你的消息</p>"
                    + "<p><a href='/test_board'>推送小黑板测试数据</a></p>"
                    + "<form action='/data' method='get'>"
                    + "<input name='msg' placeholder='输入推送内容' size='40'>"
                    + "<button type='submit'>推送</button>"
                    + "</form>"
                    + "</body></html>";
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        }

        if (dataListener != null) {
            dataListener.onDataReceived(msg);
        }

        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("msg", msg);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString());
    }

    private Response handlePostData(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> body = new HashMap<>();
        session.parseBody(body);
        String postData = body.get("postData");

        String displayText = null;

        if (postData != null && !postData.isEmpty()) {
            try {
                JSONObject json = new JSONObject(postData);
                displayText = json.toString();
            } catch (JSONException e) {
                displayText = postData;
            }
        }

        if (displayText != null && dataListener != null) {
            dataListener.onDataReceived(displayText);
        }

        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("received", displayText != null ? "json_parsed" : "empty");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString());
    }

    /**
     * 推送小黑板测试数据，模拟URA面板
     */
    private Response handleTestBoard() {
        JSONObject test = new JSONObject();
        try {
            test.put("turn", "Classic 1年");
            test.put("total", 3248);
            test.put("pt", 442);
            test.put("stamina", 64);
            test.put("max_stamina", 100);
            test.put("motivation", "好調");
            test.put("recommend", "耐力 SP訓練 4人/友情2/失敗率6%");
            test.put("recommend_type", "stamina");

            JSONObject spd = new JSONObject();
            spd.put("current", 1050); spd.put("remain", 550); spd.put("gain", 99); spd.put("pt", 13);
            test.put("speed", spd);

            JSONObject sta = new JSONObject();
            sta.put("current", 685); sta.put("remain", 915); sta.put("gain", 47); sta.put("pt", 18);
            test.put("stamina_stat", sta);

            JSONObject pwr = new JSONObject();
            pwr.put("current", 959); pwr.put("remain", 641); pwr.put("gain", 1); pwr.put("pt", 0);
            test.put("power", pwr);

            JSONObject gut = new JSONObject();
            gut.put("current", 554); gut.put("remain", 1046); gut.put("gain", 21); gut.put("pt", 7);
            test.put("guts", gut);

            JSONObject wit = new JSONObject();
            wit.put("current", 543); wit.put("remain", 1057); wit.put("gain", 28); wit.put("pt", 9);
            test.put("wisdom", wit);

            test.put("facility", "2 5 4 4 3");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        String jsonStr = test.toString();
        if (dataListener != null) {
            dataListener.onDataReceived(jsonStr);
        }

        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("action", "test_board_pushed");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString());
    }
}
