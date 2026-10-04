package com.umaai.assistant.service;
public final class EngineProtocol {
    public static final int INIT = 1, EVALUATE = 2, CANCEL = 3, SHUTDOWN = 4, REVIEW = 5;
    public static final int READY = 10, RESULT = 11, EVENT = 12, ERROR = 13;
    public static final int SELECT_COLLECTOR=6,COLLECTOR_SELECTED=14;
    private EngineProtocol() {}
}
