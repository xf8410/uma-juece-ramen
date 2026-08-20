package com.umaai.assistant.service;

/**
 * Values copied from xulai1001/umaai-rs at
 * ead54762fedc25cafdf2759846d4396a6333aa40.
 */
public final class RamenUpstreamData {
    public static final String COMMIT = "ead54762fedc25cafdf2759846d4396a6333aa40";
    public static final int SCENARIO_ID = 14;
    public static final int FEELING_THRESHOLD = 7;
    public static final int FEELING_CAPACITY = 10;
    public static final int SPECIAL_FEELING_CAPACITY = 4;
    public static final int MAX_SPECIAL_PER_RAMEN = 2;

    /** External one-based turn numbers shown by hlpatch/UI. */
    public static final int[] RMJ_TURNS = {24, 48, 72};
    public static final int SUPER_RAMEN_FIRST_TURN = 73;
    public static final int SUPER_RAMEN_LAST_TURN = 78;

    public static final int[] RMJ_SUCCESS_PT = {1500, 3000, 3500, 5000};
    public static final int[] GAIN_PT_BASE = {300, 400, 500};
    public static final int[] GAIN_PT_DELTA = {30, 40, 50};
    public static final int[] FIVE_STATUS_LIMIT_DATA = {3100, 2400, 2200, 2200, 2400};
    public static final int SIMULATOR_LIMIT_CLAMP = 2800;

    /** Region IDs 1..10 and 11..20 share these recipe rows. */
    public static final int[][] REGION_FEELING = {
            {2, 2, 1}, {1, 2, 2}, {3, 1, 1}, {2, 3, 0}, {1, 1, 3},
            {2, 0, 3}, {3, 2, 0}, {0, 3, 2}, {2, 1, 2}, {1, 3, 1}
    };

    private RamenUpstreamData() {}

    public static int yearIndexForExternalTurn(int turn) {
        if (turn < 1 || turn > SUPER_RAMEN_LAST_TURN) return -1;
        if (turn <= RMJ_TURNS[0]) return 0;
        if (turn <= RMJ_TURNS[1]) return 1;
        return 2;
    }

    public static int recipeIndexForRegionId(int regionId) {
        if (regionId < 1 || regionId > 20) return -1;
        return (regionId - 1) % 10;
    }
}
