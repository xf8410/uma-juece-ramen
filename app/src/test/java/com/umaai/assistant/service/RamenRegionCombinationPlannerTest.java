package com.umaai.assistant.service;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/** JUnit tests for RamenRegionCombinationPlanner (run via testDebugUnitTest). */
public class RamenRegionCombinationPlannerTest {

    private static String regionCatalog() {
        StringBuilder regions = new StringBuilder();
        String[] names = {"札幌", "函館", "福島", "新潟", "東京"};
        for (int i = 1; i <= 5; i++) {
            if (i > 1) regions.append(',');
            regions.append("{\"region_id\":").append(i)
                    .append(",\"region_select_type\":1,\"name_ja\":\"").append(names[i - 1]).append("\"}");
        }
        return "{\"selection_phases\":[{\"turn\":3,\"region_select_type\":1}],"
                + "\"regions\":[" + regions + "]}";
    }

    private static String resourceCatalog(int... costs) {
        StringBuilder recipes = new StringBuilder();
        for (int i = 1; i <= costs.length; i++) {
            if (i > 1) recipes.append(',');
            recipes.append("{\"region_id\":").append(i)
                    .append(",\"cost\":{\"1\":").append(costs[i - 1])
                    .append(",\"2\":0,\"3\":0}}");
        }
        return "{\"recipes\":[" + recipes + "]}";
    }

    private static String summary(String ramenFields) {
        return "{\"turn\":3,\"ramen\":{\"selected_region_ids\":[],"
                + "\"selectable_region_ids_derived\":[1,2,3,4,5],"
                + "\"selectable_region_ids_source\":\"mdb_pool_minus_all_selected_derivation\","
                + ramenFields + "}}";
    }

    // throws Exception: Android's org.json.JSONException is checked; harmless
    // when the Maven org.json (unchecked) wins the test classpath.
    private static String plan(String summaryJson, String resources) throws Exception {
        return RamenRegionCombinationPlanner.buildSummary(
                new JSONObject(summaryJson), regionCatalog(), resources);
    }

    @Test
    public void normalInventoryIsConsumedAcrossBowls() throws Exception {
        // 5 in stock, every bowl costs 5 of type 1: solo 3, in-a-row only 1.
        String out = plan(summary("\"sozai\":[5,0,0],\"special_feeling_num\":0"),
                resourceCatalog(5, 5, 5, 5, 5));
        assertTrue(out, out.contains("连做1/3"));
        assertTrue(out, out.contains("单做3/3"));
    }

    @Test
    public void universalStockIsConsumedAcrossBowls() throws Exception {
        // special=2, every bowl missing 2: only one bowl coverable in a row.
        String out = plan(summary("\"sozai\":[0,0,0],\"special_feeling_num\":2"),
                resourceCatalog(2, 2, 2, 2, 2));
        assertTrue(out, out.contains("连做1/3"));
        assertTrue(out, out.contains("万能2"));
    }

    @Test
    public void derivedPoolIsConsumedWithMatchingSource() throws Exception {
        String out = plan(summary("\"sozai\":[0,0,0],\"special_feeling_num\":2"),
                resourceCatalog(2, 2, 2, 2, 2));
        assertTrue(out, out.contains("插件MDB推导"));
    }

    @Test
    public void derivedPoolRejectedOnWrongSource() throws Exception {
        String badSource = summary("\"sozai\":[99,99,99],\"special_feeling_num\":9")
                .replace("mdb_pool_minus_all_selected_derivation", "unknown");
        String out = plan(badSource, resourceCatalog(1, 1, 1, 1, 1));
        assertTrue(out, out.contains("阶段目录回退"));
    }

    @Test
    public void runtimePoolHasPriorityOverDerived() throws Exception {
        String runtimeFirst = summary("\"sozai\":[99,99,99],\"special_feeling_num\":9,"
                + "\"selectable_region_ids\":[1,2,3]");
        String out = plan(runtimeFirst, resourceCatalog(1, 1, 1, 1, 1));
        assertTrue(out, out.contains("实时数据"));
    }

    @Test
    public void permutationSearchFindsBestOrder() throws Exception {
        String out = plan(summary("\"sozai\":[5,0,0],\"special_feeling_num\":0"),
                resourceCatalog(5, 0, 0, 0, 0));
        assertTrue(out, out.contains("连做3/3"));
    }

    @Test
    public void duplicateIdsAreDedupedBeforeSizeCheck() throws Exception {
        // [1,1,2] dedupes to 2 ids -> must NOT be accepted; derived pool used.
        String dupRuntime = summary("\"sozai\":[99,99,99],\"special_feeling_num\":9,"
                + "\"selectable_region_ids\":[1,1,2]");
        String out = plan(dupRuntime, resourceCatalog(1, 1, 1, 1, 1));
        assertTrue(out, out.contains("插件MDB推导"));
        // duplicated derived pool with wrong effective size falls to catalog
        String dupDerived = summary("\"sozai\":[99,99,99],\"special_feeling_num\":9")
                .replace("[1,2,3,4,5]", "[1,1,2]");
        out = plan(dupDerived, resourceCatalog(1, 1, 1, 1, 1));
        assertTrue(out, out.contains("阶段目录回退"));
    }

    @Test
    public void deficitLabelStatesItsDefinition() throws Exception {
        String out = plan(summary("\"sozai\":[0,0,0],\"special_feeling_num\":2"),
                resourceCatalog(2, 2, 2, 2, 2));
        assertTrue(out, out.contains("单做缺口合计"));
    }
}
