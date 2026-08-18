package lat.saturn.addon.modules;

import lat.saturn.addon.LogoBuilder;
import lat.saturn.addon.schematic.Schematic;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ProvidedStringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class VerticalBuilder extends Module {
    private static final int ROTATION_PRIORITY = 50;
    private static final int MAX_RENDER = 4000;
    private static final int GLIDE_RETRY_TICKS = 10;
    private static final int TICK_MS = 50;
    private static final int MIN_SETTLE_MS = 500;
    private static final int MAX_SETTLE_MS = 5000;
    private static final int SETTLE_PADDING_MS = 100;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPlace = settings.createGroup("Placing");
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<String> schematicFile = sgGeneral.add(new ProvidedStringSetting.Builder()
        .name("schematic")
        .description("Schematic to build, loaded from the 'schematics' folder in your game directory.")
        .defaultValue("")
        .supplier(VerticalBuilder::listSchematics)
        .onChanged(v -> {
            if (isActive()) {
                load();
                reconcileElytra();
            }
        })
        .build()
    );

    private final Setting<Integer> x = sgGeneral.add(new IntSetting.Builder()
        .name("x").description("World X the schematic origin maps to.")
        .defaultValue(0).noSlider().onChanged(v -> onAnchorChanged()).build());
    private final Setting<Integer> y = sgGeneral.add(new IntSetting.Builder()
        .name("y").description("World Y the schematic origin (bottom) maps to.")
        .defaultValue(64).noSlider().onChanged(v -> onAnchorChanged()).build());
    private final Setting<Integer> z = sgGeneral.add(new IntSetting.Builder()
        .name("z").description("World Z the schematic origin maps to.")
        .defaultValue(0).noSlider().onChanged(v -> onAnchorChanged()).build());

    private final Setting<Boolean> airPlace = sgPlace.add(new BoolSetting.Builder()
        .name("air-place").description("Places blocks in the air")
        .defaultValue(true).build());
    private final Setting<Double> range = sgPlace.add(new DoubleSetting.Builder()
        .name("range").description("Maximum distance to a block for it to be placed.")
        .defaultValue(4.5).min(0).sliderRange(1, 6).build());
    private final Setting<Integer> blocksPerTick = sgPlace.add(new IntSetting.Builder()
        .name("blocks-per-tick").description("How many blocks to place each tick.")
        .defaultValue(1).min(1).sliderRange(1, 16).build());
    private final Setting<Integer> delay = sgPlace.add(new IntSetting.Builder()
        .name("delay").description("Ticks to wait after a placement batch before the next.")
        .defaultValue(0).min(0).sliderRange(0, 20).build());
    private final Setting<Integer> maxRetries = sgPlace.add(new IntSetting.Builder()
        .name("max-retries").description("Placement passes for a block that remains missing before giving up.")
        .defaultValue(8).min(1).sliderRange(1, 30).build());

    private final Setting<Boolean> fly = sgFlight.add(new BoolSetting.Builder()
        .name("auto-fly")
        .description("Fly to the nearest unplaced block using Meteor's ElytraFly (needs an elytra).")
        .defaultValue(true)
        .onChanged(v -> { if (isActive()) reconcileElytra(); })
        .build());
    private final Setting<Integer> standoff = sgFlight.add(new IntSetting.Builder()
        .name("standoff")
        .description("How many blocks out from the wall to fly. Keep below your range.")
        .defaultValue(2).min(1).sliderRange(1, 5).visible(fly::get).build());

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render").description("Show ghost outlines of blocks still to place.")
        .defaultValue(true).build());
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the ghost boxes are drawn.")
        .defaultValue(ShapeMode.Both).visible(render::get).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color").description("Fill colour of ghost boxes.")
        .defaultValue(new SettingColor(100, 170, 255, 30)).visible(render::get).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color").description("Outline colour of ghost boxes.")
        .defaultValue(new SettingColor(100, 170, 255, 180)).visible(render::get).build());

    private Schematic schematic;
    private int delayTimer;
    private int tickCounter;

    private int relMinX, relMaxX, relMinY, relMaxY, relMinZ, relMaxZ;

    private final Map<BlockPos, Integer> placementPasses = new HashMap<>();
    private final Set<BlockPos> pending = new HashSet<>();
    private final Set<BlockPos> failed = new HashSet<>();
    private boolean settling;
    private int settleUntilTick;

    private boolean normalIsZ;
    private int standSign = 1;
    private boolean announcedComplete;
    private boolean enabledElytra;
    private boolean modifiedElytra;
    private boolean prevAutoPilot;
    private double prevAutoPilotMinHeight;
    private boolean warnedNoElytra;
    private int lastGlideAttemptTick;

    private final ResetSync resetSync = new ResetSync();
    private class ResetSync {
        @EventHandler
        private void onTick(TickEvent.Pre event) { syncResetDefaultsToPlayer(); }
    }

    public VerticalBuilder() {
        super(LogoBuilder.CATEGORY, "vertical-builder", "Builds spawn logos based on schematics");

        MeteorClient.EVENT_BUS.subscribe(resetSync);
    }

    @Override
    public void onActivate() {
        if (mc.player == null) { toggle(); return; }
        delayTimer = 0;
        warnedNoElytra = false;
        lastGlideAttemptTick = -GLIDE_RETRY_TICKS;
        load();
        reconcileElytra();
    }

    @Override
    public void onDeactivate() {
        schematic = null;
        releaseFlightKeys();
        restoreElytra();
    }

    private void load() {
        schematic = null;
        clearProgress();
        String name = schematicFile.get();
        if (name == null || name.isEmpty()) {
            warning("No schematic selected. Drop a .litematic, .schem, or .schematic in the 'schematics' folder.");
            return;
        }
        try {
            Schematic loaded = Schematic.load(schematicsDir().resolve(name));
            if (loaded.entries.isEmpty()) { error("'%s' has no blocks.", name); return; }

            relMinX = relMinY = relMinZ = Integer.MAX_VALUE;
            relMaxX = relMaxY = relMaxZ = Integer.MIN_VALUE;
            for (Schematic.Entry e : loaded.entries) {
                BlockPos p = e.pos();
                relMinX = Math.min(relMinX, p.getX()); relMaxX = Math.max(relMaxX, p.getX());
                relMinY = Math.min(relMinY, p.getY()); relMaxY = Math.max(relMaxY, p.getY());
                relMinZ = Math.min(relMinZ, p.getZ()); relMaxZ = Math.max(relMaxZ, p.getZ());
            }

            schematic = loaded;
            computeOrientation();
            announcedComplete = false;
            int thickness = 1 + Math.min(relMaxX - relMinX, relMaxZ - relMinZ);
            info("Loaded %s (%d blocks, %d tall, %d thick).",
                name, schematic.entries.size(), relMaxY - relMinY + 1, thickness);
        } catch (Exception e) {
            error("Failed to load %s: %s", name, e.getMessage());
        }
    }

    private void onAnchorChanged() {
        clearProgress();
        if (isActive()) computeOrientation();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (schematic == null || mc.player == null || mc.world == null) return;
        tickCounter++;

        if (pending.isEmpty() && remaining() == 0) {
            if (!announcedComplete) {
                if (failed.isEmpty()) info("Schematic complete, disabling.");
                else warning("No placeable blocks remain; disabling with %d failed block(s).", failed.size());
                announcedComplete = true;
            }
            toggle();
            return;
        }

        if (settling) {
            if (tickCounter >= settleUntilTick) finishSettlement();
            return;
        }

        if (fly.get()) driveFly();

        if (delayTimer > 0) { delayTimer--; return; }
        placeTick();
    }

    private void placeTick() {
        int placed = 0;
        Vec3d eye = mc.player.getEyePos();

        for (Schematic.Entry entry : schematic.entries) {
            if (placed >= blocksPerTick.get()) break;

            BlockPos pos = worldPos(entry.pos());
            BlockState desired = entry.state();
            BlockState current = mc.world.getBlockState(pos);

            if (pending.contains(pos)) continue;
            if (current.equals(desired)) { clearTracking(pos); continue; }
            if (failed.contains(pos)) continue;
            if (!current.isAir() && !current.isReplaceable()) continue;
            if (eye.distanceTo(Vec3d.ofCenter(pos)) > range.get()) continue;

            int passes = placementPasses.getOrDefault(pos, 0);
            if (passes >= maxRetries.get()) {
                failed.add(pos);
                placementPasses.remove(pos);
                warning("Gave up on block at %d, %d, %d after %d placement passes.",
                    pos.getX(), pos.getY(), pos.getZ(), passes);
                continue;
            }

            Item item = desired.getBlock().asItem();
            if (item == Items.AIR) continue;

            FindItemResult found = InvUtils.findInHotbar(item);
            if (!found.found()) continue;

            boolean attempted = place(pos, found);

            if (attempted) {
                pending.add(pos);
                placementPasses.put(pos, passes + 1);
                placed++;
            }
        }

        if (placed > 0) delayTimer = delay.get();
        if (!hasAttemptableUnsentTargets()) beginSettlement();
    }

    private boolean hasAttemptableUnsentTargets() {
        Vec3d eye = mc.player.getEyePos();
        boolean canAutoTravel = fly.get() && (mc.player.isGliding()
            || mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER));

        for (Schematic.Entry entry : schematic.entries) {
            BlockPos pos = worldPos(entry.pos());
            if (!isAttemptableTarget(entry, pos)) continue;
            if (eye.distanceTo(Vec3d.ofCenter(pos)) > range.get() && !canAutoTravel) continue;

            return true;
        }
        return false;
    }

    private boolean isAttemptableTarget(Schematic.Entry entry, BlockPos pos) {
        if (pending.contains(pos) || failed.contains(pos)) return false;

        BlockState current = mc.world.getBlockState(pos);
        if (current.equals(entry.state()) || (!current.isAir() && !current.isReplaceable())) return false;

        Item item = entry.state().getBlock().asItem();
        if (item == Items.AIR || !InvUtils.findInHotbar(item).found()) return false;

        return airPlace.get() || (BlockUtils.getPlaceSide(pos) != null
            && BlockUtils.canPlaceBlock(pos, true, entry.state().getBlock()));
    }

    private void beginSettlement() {
        settling = true;
        settleUntilTick = tickCounter + settleTicks();
        setVertical(0);
    }

    private void finishSettlement() {
        for (Schematic.Entry entry : schematic.entries) {
            BlockPos pos = worldPos(entry.pos());
            BlockState current = mc.world.getBlockState(pos);

            if (current.equals(entry.state())) {
                clearTracking(pos);
                continue;
            }

            if (!current.isAir() && !current.isReplaceable()) {
                pending.remove(pos);
                placementPasses.remove(pos);
                if (failed.add(pos)) {
                    warning("Cannot place block at %d, %d, %d because the position is occupied.",
                        pos.getX(), pos.getY(), pos.getZ());
                }
                continue;
            }

            if (!pending.remove(pos)) continue;
            int passes = placementPasses.getOrDefault(pos, 0);
            if (passes >= maxRetries.get()) {
                placementPasses.remove(pos);
                failed.add(pos);
                warning("Gave up on block at %d, %d, %d after %d placement passes.",
                    pos.getX(), pos.getY(), pos.getZ(), passes);
            }
        }

        settling = false;
    }

    private int settleTicks() {
        int pingMs = 0;
        if (mc.getNetworkHandler() != null && mc.player != null) {
            PlayerListEntry playerEntry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (playerEntry != null) pingMs = Math.max(0, playerEntry.getLatency());
        }

        long dynamicSettleMs = (long) pingMs * 2 + SETTLE_PADDING_MS;
        int settleMs = (int) Math.min(MAX_SETTLE_MS, Math.max(MIN_SETTLE_MS, dynamicSettleMs));
        return Math.max(1, (settleMs + TICK_MS - 1) / TICK_MS);
    }

    private void driveFly() {
        if (!ensureGliding()) { setVertical(0); return; }

        BlockPos target = nearestStandoff();
        if (target == null) { setVertical(0); return; }

        double dx = (target.getX() + 0.5) - mc.player.getX();
        double dz = (target.getZ() + 0.5) - mc.player.getZ();
        double dy = target.getY() - mc.player.getY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        setVertical(dy > 0.6 ? 1 : dy < -0.6 ? -1 : 0);

        if (horizontal > 0.5) mc.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        mc.player.fallDistance = 0;
    }

    private boolean ensureGliding() {
        if (mc.player.isGliding()) return true;

        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER)) {
            if (!warnedNoElytra) {
                warnedNoElytra = true;
                warning("Equip an elytra — VerticalBuilder flies the wall with ElytraFly.");
            }
            return false;
        }
        warnedNoElytra = false;

        if (mc.player.isOnGround()) {
            Vec3d v = mc.player.getVelocity();
            mc.player.setVelocity(v.x, 0.42, v.z); // hop to get airborne
            mc.player.setOnGround(false);
            return false;
        }

        if (tickCounter - lastGlideAttemptTick < GLIDE_RETRY_TICKS) return false;
        lastGlideAttemptTick = tickCounter;
        mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
            mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        return false;
    }

    private void setVertical(int dir) {
        if (mc.options == null) return;
        mc.options.jumpKey.setPressed(dir > 0);
        mc.options.sneakKey.setPressed(dir < 0);
    }

    private void releaseFlightKeys() {
        if (mc.options == null) return;
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private void enableElytra() {
        if (modifiedElytra) return;
        ElytraFly ef = Modules.get().get(ElytraFly.class);
        if (ef == null) return;
        prevAutoPilot = ef.autoPilot.get();
        prevAutoPilotMinHeight = ef.autoPilotMinimumHeight.get();
        modifiedElytra = true;
        ef.autoPilot.set(true);
        ef.autoPilotMinimumHeight.set(-128.0);
        if (!ef.isActive()) { ef.enable(); enabledElytra = true; }
    }

    private void restoreElytra() {
        if (!modifiedElytra) return;
        ElytraFly ef = Modules.get().get(ElytraFly.class);
        if (ef == null) {
            modifiedElytra = false;
            enabledElytra = false;
            return;
        }
        ef.autoPilot.set(prevAutoPilot);
        ef.autoPilotMinimumHeight.set(prevAutoPilotMinHeight);
        if (enabledElytra && ef.isActive()) ef.disable();
        enabledElytra = false;
        modifiedElytra = false;
    }

    private void reconcileElytra() {
        if (isActive() && schematic != null && fly.get()) enableElytra();
        else restoreElytra();
    }

    private void computeOrientation() {
        if (schematic == null || mc.player == null) return;
        normalIsZ = (relMaxX - relMinX) >= (relMaxZ - relMinZ);
        if (normalIsZ) {
            double centreZ = (z.get() + relMinZ + z.get() + relMaxZ) / 2.0;
            standSign = mc.player.getZ() >= centreZ ? 1 : -1;
        } else {
            double centreX = (x.get() + relMinX + x.get() + relMaxX) / 2.0;
            standSign = mc.player.getX() >= centreX ? 1 : -1;
        }
    }

    private BlockPos nearestStandoff() {
        Vec3d eye = mc.player.getEyePos();
        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        for (Schematic.Entry e : schematic.entries) {
            BlockPos pos = worldPos(e.pos());
            if (!isAttemptableTarget(e, pos)) continue;
            double d = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (d < best) { best = d; bestPos = pos; }
        }
        if (bestPos == null) return null;
        int out = standSign * standoff.get();
        return normalIsZ ? bestPos.add(0, 0, out) : bestPos.add(out, 0, 0);
    }

    private int remaining() {
        int r = 0;
        for (Schematic.Entry e : schematic.entries) {
            BlockPos pos = worldPos(e.pos());
            if (!failed.contains(pos) && !mc.world.getBlockState(pos).equals(e.state())) r++;
        }
        return r;
    }

    private boolean place(BlockPos pos, FindItemResult item) {
        if (!airPlace.get()) {
            if (BlockUtils.getPlaceSide(pos) == null) return false;
            return BlockUtils.place(pos, item, true, ROTATION_PRIORITY, true, true);
        }

        Hand hand;
        boolean swapped = false;
        if (item.isOffhand()) {
            hand = Hand.OFF_HAND;
        } else if (item.isHotbar()) {
            InvUtils.swap(item.slot(), true);
            swapped = true;
            hand = Hand.MAIN_HAND;
        } else {
            return false;
        }

        Vec3d hit = Vec3d.ofCenter(pos);
        BlockUtils.interact(new BlockHitResult(hit, Direction.UP, pos, false), hand, true);
        if (swapped) InvUtils.swapBack();
        return true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || schematic == null || mc.world == null) return;
        int shown = 0;
        for (Schematic.Entry entry : schematic.entries) {
            if (shown >= MAX_RENDER) break;
            BlockPos pos = worldPos(entry.pos());
            if (mc.world.getBlockState(pos).equals(entry.state())) continue;
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            shown++;
        }
    }

    private BlockPos worldPos(BlockPos relative) {
        return new BlockPos(x.get() + relative.getX(), y.get() + relative.getY(), z.get() + relative.getZ());
    }

    private void clearProgress() {
        placementPasses.clear();
        pending.clear();
        failed.clear();
        settling = false;
        settleUntilTick = 0;
        tickCounter = 0;
    }

    private void clearTracking(BlockPos pos) {
        placementPasses.remove(pos);
        pending.remove(pos);
        failed.remove(pos);
    }

    private void syncResetDefaultsToPlayer() {
        if (mc.player == null) return;
        Field field = defaultValueField();
        if (field == null) return;
        try {
            field.set(x, mc.player.getBlockX());
            field.set(y, mc.player.getBlockY());
            field.set(z, mc.player.getBlockZ());
        } catch (IllegalAccessException e) {
            LogoBuilder.LOG.warn("Could not update Vertical Builder's reset coordinates.", e);
            DEFAULT_VALUE_FIELD = null;
            defaultValueLookupFailed = true;
        }
    }

    private static Field DEFAULT_VALUE_FIELD;
    private static boolean defaultValueLookupAttempted;
    private static boolean defaultValueLookupFailed;

    private static Field defaultValueField() {
        if (!defaultValueLookupAttempted) {
            defaultValueLookupAttempted = true;
            try {
                // Meteor 1.21.11 has no supported setter for a Setting's reset value.
                // This exact field is verified against the resolved 1.21.11-86 sources.
                Field f = Setting.class.getDeclaredField("defaultValue");
                f.setAccessible(true);
                DEFAULT_VALUE_FIELD = f;
            } catch (NoSuchFieldException | RuntimeException e) {
                defaultValueLookupFailed = true;
                LogoBuilder.LOG.warn("Meteor no longer exposes the Setting reset value; dynamic reset coordinates are disabled.", e);
            }
        }
        return defaultValueLookupFailed ? null : DEFAULT_VALUE_FIELD;
    }

    private static Path schematicsDir() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir;
    }

    private static String[] listSchematics() {
        Path dir = schematicsDir();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(n -> {
                    String l = n.toLowerCase();
                    return l.endsWith(".litematic") || l.endsWith(".schem") || l.endsWith(".schematic");
                })
                .sorted()
                .toArray(String[]::new);
        } catch (IOException e) {
            return new String[0];
        }
    }
}
