package com.xuanfei.mcpcontrol.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xuanfei.mcpcontrol.mcp.BaseTool;
import com.xuanfei.mcpcontrol.mcp.JsonUtil;
import com.xuanfei.mcpcontrol.mcp.ToolContext;
import com.xuanfei.mcpcontrol.mcp.ToolData;
import com.xuanfei.mcpcontrol.mcp.ToolError;
import com.xuanfei.mcpcontrol.mcp.ToolRegistry;
import com.xuanfei.mcpcontrol.mcp.ToolResult;
import com.xuanfei.mcpcontrol.mcp.ToolSchemas;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Crafting-table recipes driven directly through the recipe manager. */
public final class CraftTools {

    private CraftTools() {
    }

    public static void register(ToolRegistry registry) {
        registry.register(new BaseTool("craft_item", "Craft item",
                "Crafts an item using a matching crafting-table recipe, taking ingredients from the inventory. "
                        + "Only works when the player has the required ingredients; no crafting table block is needed.",
                craftSchema(), false, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    ServerPlayer player = context.player();
                    String wanted = normalizeItem(JsonUtil.requiredString(copy, "item"));
                    int wantedCount = JsonUtil.optionalInt(copy, "count", 1, 1, 64);
                    ServerLevel level = player.serverLevel();

                    RecipeHolder<?> holder = findRecipe(context, level, wanted);
                    if (holder == null) {
                        return ToolResult.error("No crafting recipe produces " + wanted + ".");
                    }
                    CraftingRecipe recipe = (CraftingRecipe) holder.value();
                    int outputPerCraft = recipe.getResultItem(level.registryAccess()).getCount();
                    int crafts = Math.max(1, (wantedCount + outputPerCraft - 1) / outputPerCraft);

                    int crafted = 0;
                    int completedCrafts = 0;
                    for (int i = 0; i < crafts; i++) {
                        Plan plan = buildPlan(player, level, recipe);
                        if (plan == null) {
                            break;
                        }
                        ItemStack result = recipe.assemble(plan.input(), level.registryAccess());
                        if (result.isEmpty()) {
                            break;
                        }
                        consume(player, plan.consume());
                        if (!player.getInventory().add(result)) {
                            player.drop(result, false);
                        }
                        crafted += result.getCount();
                        completedCrafts++;
                    }
                    player.inventoryMenu.broadcastChanges();

                    JsonObject data = new JsonObject();
                    data.addProperty("item", wanted);
                    data.addProperty("recipe", holder.id().toString());
                    data.addProperty("completed_crafts", completedCrafts);
                    data.addProperty("crafted_count", crafted);
                    data.addProperty("requested_count", wantedCount);
                    data.add("remaining", ToolData.inventory(player));
                    if (completedCrafts == 0) {
                        return ToolResult.error("Missing ingredients for " + wanted + ". Recipe: " + holder.id(), data);
                    }
                    return ToolResult.ok("Crafted " + crafted + " " + wanted, data);
                });
            }
        });

        registry.register(new BaseTool("list_recipes", "List recipes",
                "Lists crafting recipes whose output matches an item id. Includes the recipe id, output count and ingredient ids.",
                recipesSchema(), true, false) {
            @Override
            public CompletableFuture<ToolResult> call(JsonObject arguments, ToolContext context) {
                JsonObject copy = arguments.deepCopy();
                return context.sync(() -> {
                    String filter = normalizeItem(JsonUtil.optionalString(copy, "item", ""));
                    int limit = JsonUtil.optionalInt(copy, "max_results", 20, 1, 100);
                    JsonArray recipes = new JsonArray();
                    int total = 0;
                    for (RecipeHolder<?> holder : context.server.getRecipeManager().getRecipes()) {
                        if (!(holder.value() instanceof CraftingRecipe recipe)) {
                            continue;
                        }
                        ItemStack result = recipe.getResultItem(context.server.overworld().registryAccess());
                        if (result.isEmpty()) {
                            continue;
                        }
                        String id = JsonUtil.itemId(result);
                        if (!filter.isEmpty() && !id.equals(filter)) {
                            continue;
                        }
                        total++;
                        if (recipes.size() >= limit) {
                            continue;
                        }
                        JsonObject entry = new JsonObject();
                        entry.addProperty("recipe", holder.id().toString());
                        entry.addProperty("output", id);
                        entry.addProperty("count", result.getCount());
                        JsonArray ingredients = new JsonArray();
                        for (Ingredient ingredient : recipe.getIngredients()) {
                            if (ingredient.isEmpty()) {
                                continue;
                            }
                            ItemStack[] items = ingredient.getItems();
                            if (items.length > 0) {
                                ingredients.add(JsonUtil.itemId(items[0]));
                            }
                        }
                        entry.add("ingredients", ingredients);
                        recipes.add(entry);
                    }
                    JsonObject data = new JsonObject();
                    data.addProperty("filter", filter);
                    data.addProperty("total", total);
                    data.add("recipes", recipes);
                    return ToolResult.ok("Found " + total + " crafting recipes", data);
                });
            }
        });
    }

    private static RecipeHolder<?> findRecipe(ToolContext context, ServerLevel level, String wanted) {
        for (RecipeHolder<?> holder : context.server.getRecipeManager().getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe recipe)) {
                continue;
            }
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (!result.isEmpty() && JsonUtil.itemId(result).equals(wanted)) {
                return holder;
            }
        }
        return null;
    }

    private static Plan buildPlan(ServerPlayer player, ServerLevel level, CraftingRecipe recipe) {
        int width;
        int height;
        if (recipe instanceof ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
        } else {
            width = 3;
            height = 3;
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        int gridSize = Math.max(width * height, ingredients.size());
        List<ItemStack> grid = new ArrayList<>();
        for (int i = 0; i < gridSize; i++) {
            grid.add(ItemStack.EMPTY);
        }

        Map<Item, Integer> available = countItems(player.getInventory());
        List<ItemStack> consume = new ArrayList<>();
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient.isEmpty()) {
                continue;
            }
            Item chosen = null;
            for (ItemStack candidate : ingredient.getItems()) {
                if (!candidate.isEmpty() && available.getOrDefault(candidate.getItem(), 0) > 0) {
                    chosen = candidate.getItem();
                    break;
                }
            }
            if (chosen == null) {
                return null;
            }
            available.merge(chosen, -1, Integer::sum);
            ItemStack stack = new ItemStack(chosen);
            if (i < grid.size()) {
                grid.set(i, stack);
            }
            consume.add(stack);
        }

        CraftingInput input = CraftingInput.of(width, height, grid);
        if (!recipe.matches(input, level)) {
            // Shapeless recipes may not line up row-major; try a compact layout.
            if (!(recipe instanceof ShapedRecipe) && ingredients.size() <= 9) {
                List<ItemStack> compact = new ArrayList<>();
                for (ItemStack stack : consume) {
                    compact.add(stack);
                }
                while (compact.size() < 9) {
                    compact.add(ItemStack.EMPTY);
                }
                CraftingInput compactInput = CraftingInput.of(3, 3, compact);
                if (recipe.matches(compactInput, level)) {
                    return new Plan(compactInput, consume);
                }
            }
            return null;
        }
        return new Plan(input, consume);
    }

    private static Map<Item, Integer> countItems(Inventory inventory) {
        Map<Item, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static void consume(ServerPlayer player, List<ItemStack> ingredients) {
        for (ItemStack wanted : ingredients) {
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.isEmpty() && stack.getItem() == wanted.getItem()) {
                    stack.shrink(1);
                    player.getInventory().setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                    break;
                }
            }
        }
        player.inventoryMenu.broadcastChanges();
    }

    private record Plan(CraftingInput input, List<ItemStack> consume) {
    }

    private static String normalizeItem(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
    }

    private static JsonObject craftSchema() {
        JsonObject properties = new JsonObject();
        properties.add("item", ToolSchemas.string("Item id to craft, e.g. minecraft:iron_pickaxe"));
        properties.add("count", ToolSchemas.integer("Desired output count", 1, 64));
        return ToolSchemas.schema(properties, "item");
    }

    private static JsonObject recipesSchema() {
        JsonObject properties = new JsonObject();
        properties.add("item", ToolSchemas.string("Optional output item id filter"));
        properties.add("max_results", ToolSchemas.integer("Maximum recipes to return", 1, 100));
        return ToolSchemas.schema(properties);
    }
}
