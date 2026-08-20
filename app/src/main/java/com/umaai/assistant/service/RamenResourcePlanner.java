package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario 14 resource-only planner.
 *
 * It consumes runtime RemainTurn counters and final per-command reduction vectors.
 * It deliberately does not reconstruct natural/region/camp/partner/card formulas and
 * does not replace the normal training-value evaluator.
 */
public final class RamenResourcePlanner {
    private static final String[] FEELING_NAMES = {"?", "面", "汤", "配"};

    private RamenResourcePlanner() {}

    public static String buildSummary(JSONObject ramen, String resourceCatalogRaw,
                                      String gaugeCatalogRaw, String regionCatalogRaw) {
        if (ramen == null || resourceCatalogRaw == null || gaugeCatalogRaw == null
                || regionCatalogRaw == null) return "";
        try {
            JSONObject resources = new JSONObject(resourceCatalogRaw);
            JSONObject gauges = new JSONObject(gaugeCatalogRaw);
            int threshold = gauges.getJSONObject("acquisition_rules").getInt("threshold");
            int capacity = gauges.getJSONObject("acquisition_rules")
                    .getJSONObject("inventory").getInt("shared_capacity");
            if (threshold <= 0 || capacity <= 0) return "";

            List<Recipe> recipes = readSelectedRecipes(resources, new JSONObject(regionCatalogRaw),
                    ramen.optJSONArray("selected_region_ids"));
            if (recipes.isEmpty()) return "";
            int special = Math.max(0, ramen.optInt("special_feeling_num", 0));
            List<Integer> queue = readInventoryQueue(ramen);
            List<Recipe> current = feasibleRecipes(recipes, countQueue(queue), special);
            if (!current.isEmpty()) return "现在可吃：" + formatRecipes(current);

            Map<Integer, Integer> currentRemaining = readCurrentRemaining(
                    ramen.optJSONArray("acquisition_gauges"));
            JSONArray vectors = ramen.optJSONArray("command_gauge_vectors");
            if (vectors == null || vectors.length() == 0 || currentRemaining.size() < 3) {
                return "暂时不能吃面";
            }

            for (int i = 0; i < vectors.length(); i++) {
                JSONObject vector = vectors.optJSONObject(i);
                if (vector == null) continue;
                int commandId = vector.optInt("command_id", -1);
                JSONArray progress = vector.optJSONArray("progress");
                if (commandId < 0 || progress == null) continue;
                Projection projection = project(queue, currentRemaining, progress, threshold, capacity);
                List<Recipe> after = feasibleRecipes(recipes, countQueue(projection.queue), special);
                if (!after.isEmpty()) {
                    return "建议：先" + commandName(commandId) + "训，再吃" + after.get(0).name + "面";
                }
            }
            return "暂时不能吃面";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static List<Integer> readInventoryQueue(JSONObject ramen) {
        JSONArray info = ramen.optJSONArray("feeling_info");
        List<IndexedFeeling> indexed = new ArrayList<>();
        if (info != null) {
            for (int i = 0; i < info.length(); i++) {
                JSONObject item = info.optJSONObject(i);
                if (item == null) continue;
                int id = item.optInt("FeelingId", item.optInt("feeling_id", -1));
                int index = item.optInt("FeelingIndex", item.optInt("feeling_index", i));
                if (id >= 1 && id <= 3) indexed.add(new IndexedFeeling(index, id));
            }
        }
        Collections.sort(indexed, Comparator.comparingInt(x -> x.index));
        List<Integer> queue = new ArrayList<>();
        for (IndexedFeeling item : indexed) queue.add(item.feelingId);

        // Old summaries may omit feeling_info. Counts remain usable for affordability;
        // synthesized order is not used to claim which type is evicted.
        if (queue.isEmpty()) {
            JSONArray sozai = ramen.optJSONArray("sozai");
            if (sozai != null) {
                for (int id = 1; id <= 3; id++) {
                    for (int n = 0; n < Math.max(0, sozai.optInt(id - 1, 0)); n++) queue.add(id);
                }
            }
        }
        return queue;
    }

    private static Map<Integer, Integer> readCurrentRemaining(JSONArray array) {
        Map<Integer, Integer> result = new HashMap<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            int id = item.optInt("feeling_id", -1);
            int remaining = item.optInt("remaining", -1);
            if (id >= 1 && id <= 3 && remaining >= 0) result.put(id, remaining);
        }
        return result;
    }

    private static Map<Integer, Integer> readCommandFeelings(JSONArray array) {
        Map<Integer, Integer> result = new HashMap<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            int commandId = item.optInt("command_id", -1);
            int feelingId = item.optInt("feeling_id", -1);
            if (commandId >= 0 && feelingId >= 1 && feelingId <= 3) {
                result.put(commandId, feelingId);
                Integer alternate = alternateCommandId(commandId);
                if (alternate != null) result.put(alternate, feelingId);
            }
        }
        return result;
    }

    private static Projection project(List<Integer> currentQueue,
                                      Map<Integer, Integer> currentRemaining,
                                      JSONArray progress, int threshold, int capacity) {
        List<Integer> queue = new ArrayList<>(currentQueue);
        List<Integer> gained = new ArrayList<>();
        int[] reductions = new int[4];
        int evicted = 0;
        for (int i = 0; i < progress.length(); i++) {
            JSONObject item = progress.optJSONObject(i);
            if (item == null) continue;
            int id = item.optInt("feeling_id", -1);
            int reduction = item.optInt("remaining", -1);
            if (id < 1 || id > 3 || reduction < 0) continue;
            reductions[id] = reduction;
            Integer remaining = currentRemaining.get(id);
            if (remaining != null && reduction >= remaining) {
                gained.add(id);
                while (queue.size() >= capacity) {
                    queue.remove(0);
                    evicted++;
                }
                queue.add(id);
                // Excess reduction is discarded; the next counter resets to threshold.
                currentRemaining = new HashMap<>(currentRemaining);
                currentRemaining.put(id, threshold);
            }
        }
        return new Projection(queue, gained, reductions, evicted);
    }

    private static List<Recipe> readSelectedRecipes(JSONObject resources, JSONObject regions,
                                                     JSONArray selected) {
        Map<Integer, String> names = new HashMap<>();
        JSONArray regionRows = regions.optJSONArray("regions");
        if (regionRows != null) {
            for (int i = 0; i < regionRows.length(); i++) {
                JSONObject row = regionRows.optJSONObject(i);
                if (row != null) names.put(row.optInt("region_id", -1),
                        chineseRegionName(row.optString("name_ja", "地区")));
            }
        }
        Map<Integer, Recipe> all = new HashMap<>();
        JSONArray rows = resources.optJSONArray("recipes");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                JSONObject cost = row.optJSONObject("cost");
                if (cost == null) continue;
                int id = row.optInt("region_id", -1);
                all.put(id, new Recipe(id, names.getOrDefault(id, "地区" + id),
                        new int[]{0, cost.optInt("1"), cost.optInt("2"), cost.optInt("3")}));
            }
        }
        List<Recipe> result = new ArrayList<>();
        if (selected != null) {
            for (int i = 0; i < selected.length(); i++) {
                Recipe recipe = all.get(selected.optInt(i, -1));
                if (recipe != null) result.add(recipe);
            }
        }
        return result;
    }

    private static List<Recipe> feasibleRecipes(List<Recipe> recipes, int[] counts, int special) {
        List<Recipe> result = new ArrayList<>();
        for (Recipe recipe : recipes) {
            int missing = 0;
            for (int id = 1; id <= 3; id++) missing += Math.max(0, recipe.cost[id] - counts[id]);
            if (missing <= Math.min(2, special)) result.add(recipe);
        }
        return result;
    }

    private static String formatRecipes(List<Recipe> recipes) {
        List<String> names = new ArrayList<>();
        for (Recipe recipe : recipes) names.add(recipe.name + "面");
        return String.join("/", names);
    }

    private static String chineseRegionName(String name) {
        return name.replace("館", "馆").replace("島", "岛")
                .replace("東", "东").replace("倉", "仓");
    }

    private static int[] countQueue(List<Integer> queue) {
        int[] counts = new int[4];
        for (Integer id : queue) if (id != null && id >= 1 && id <= 3) counts[id]++;
        return counts;
    }

    private static String formatVector(int[] values) {
        return "[面" + values[1] + " 汤" + values[2] + " 配" + values[3] + "]";
    }

    private static String formatFeelings(List<Integer> ids) {
        StringBuilder out = new StringBuilder();
        for (Integer id : ids) {
            if (out.length() > 0) out.append("+");
            out.append(id >= 1 && id <= 3 ? FEELING_NAMES[id] : "?" + id);
        }
        return out.toString();
    }

    private static String commandName(int id) {
        switch (id) {
            case 101: case 601: return "速";
            case 105: case 602: return "耐";
            case 102: case 603: return "力";
            case 103: case 604: return "根";
            case 106: case 605: return "智";
            default: return "指令" + id;
        }
    }

    private static Integer alternateCommandId(int id) {
        switch (id) {
            case 101: return 601; case 601: return 101;
            case 105: return 602; case 602: return 105;
            case 102: return 603; case 603: return 102;
            case 103: return 604; case 604: return 103;
            case 106: return 605; case 605: return 106;
            default: return null;
        }
    }

    private static final class IndexedFeeling {
        final int index;
        final int feelingId;
        IndexedFeeling(int index, int feelingId) { this.index = index; this.feelingId = feelingId; }
    }

    private static final class Recipe {
        final int regionId;
        final String name;
        final int[] cost;
        Recipe(int regionId, String name, int[] cost) {
            this.regionId = regionId; this.name = name; this.cost = cost;
        }
    }

    private static final class Projection {
        final List<Integer> queue;
        final List<Integer> gained;
        final int[] reductions;
        final int evicted;
        Projection(List<Integer> queue, List<Integer> gained, int[] reductions, int evicted) {
            this.queue = queue; this.gained = gained; this.reductions = reductions; this.evicted = evicted;
        }
    }
}
