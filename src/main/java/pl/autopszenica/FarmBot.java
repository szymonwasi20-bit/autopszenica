package pl.autopszenica;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

/** Logika bota: zbiera dojrzala pszenice, sadzi nowa, odklada do najblizszej skrzyni. */
public final class FarmBot {

    private enum State { FARM, TO_CHEST, OPENING, DEPOSITING }

    // ---- ustawienia ----
    private static boolean enabled = false;
    private static boolean walk = true;
    private static int stacksThreshold = 5;       // 5 stackow * 64 = 320 pszenicy
    private static final double REACH = 4.0;      // zasieg uderzenia/sadzenia (bezpieczny)
    private static final int SCAN_WALK = 10;      // jak daleko szukac pszenicy do dojscia
    private static final int SCAN_CHEST = 24;     // jak daleko szukac skrzyni
    private static final int ACTION_DELAY = 2;    // tickow miedzy akcjami

    // ---- stan ----
    private static State state = State.FARM;
    private static int cooldown = 0;
    private static int timeout = 0;
    private static int noChestUntil = 0;
    private static int tickCounter = 0;
    private static BlockPos chestPos = null;
    private static int lastClickedSlot = -1;
    private static final Set<BlockPos> badChests = new HashSet<>();

    private FarmBot() {}

    // ================= API dla komend =================
    public static boolean isEnabled() { return enabled; }
    public static boolean isWalk() { return walk; }
    public static int getStacksThreshold() { return stacksThreshold; }
    public static String stateName() { return state.name(); }
    public static void setWalk(boolean w) { walk = w; if (!w) releaseKeys(MinecraftClient.getInstance()); }
    public static void setStacksThreshold(int n) { stacksThreshold = n; }

    public static void setEnabled(boolean on) {
        enabled = on;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!on) {
            releaseKeys(mc);
            if (mc.player != null && mc.currentScreen instanceof GenericContainerScreen<?>) {
                mc.player.closeHandledScreen();
            }
        }
        state = State.FARM;
        cooldown = 0;
        timeout = 0;
        chestPos = null;
        badChests.clear();
    }

    // ================= glowna petla =================
    public static void tick(MinecraftClient mc) {
        tickCounter++;
        if (!enabled) return;
        ClientPlayerEntity player = mc.player;
        ClientWorld world = mc.world;
        if (player == null || world == null || mc.interactionManager == null) {
            return;
        }
        if (cooldown > 0) { cooldown--; return; }

        switch (state) {
            case FARM -> tickFarm(mc, player, world);
            case TO_CHEST -> tickToChest(mc, player, world);
            case OPENING -> tickOpening(mc, player, world);
            case DEPOSITING -> tickDepositing(mc, player, world);
        }
    }

    // ---------------- FARMIENIE ----------------
    private static void tickFarm(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world) {
        if (mc.currentScreen != null) return; // nie dzialamy z otwartym GUI

        int wheat = countWheat(player);
        boolean full = !hasEmptySlot(player) && wheat > 0;
        if ((wheat >= stacksThreshold * 64 || full) && tickCounter >= noChestUntil) {
            releaseKeys(mc);
            state = State.TO_CHEST;
            chestPos = null;
            timeout = 0;
            return;
        }

        boolean haveSeeds = findSeedSlot(player) != -1;
        Vec3d eye = player.getEyePos();

        BlockPos breakTarget = null;
        double bestBreak = Double.MAX_VALUE;
        BlockPos plantTarget = null;      // pozycja powietrza nad farmlandem
        double bestPlant = Double.MAX_VALUE;

        int r = (int) Math.ceil(REACH);
        BlockPos base = player.getBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.add(dx, dy, dz);
                    double d = eye.squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d > REACH * REACH) continue;
                    BlockState bs = world.getBlockState(p);
                    if (isMatureWheat(bs)) {
                        if (d < bestBreak) { bestBreak = d; breakTarget = p; }
                    } else if (haveSeeds && bs.isAir()
                            && world.getBlockState(p.down()).isOf(Blocks.FARMLAND)) {
                        if (d < bestPlant) { bestPlant = d; plantTarget = p; }
                    }
                }
            }
        }

        if (breakTarget != null) {
            releaseKeys(mc);
            lookAt(player, Vec3d.ofCenter(breakTarget));
            mc.interactionManager.attackBlock(breakTarget, Direction.UP);
            player.swingHand(Hand.MAIN_HAND);
            cooldown = ACTION_DELAY;
            return;
        }

        if (plantTarget != null) {
            releaseKeys(mc);
            if (!selectSeeds(mc, player)) return;
            BlockPos soil = plantTarget.down();
            Vec3d hit = new Vec3d(soil.getX() + 0.5, soil.getY() + 1.0, soil.getZ() + 0.5);
            lookAt(player, hit);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                new BlockHitResult(hit, Direction.UP, soil, false));
            player.swingHand(Hand.MAIN_HAND);
            cooldown = ACTION_DELAY;
            return;
        }

        // nic w zasiegu -> idz do najblizszego celu
        if (walk) {
            BlockPos far = findFarTarget(world, player, haveSeeds);
            if (far != null) {
                walkTo(mc, player, Vec3d.ofCenter(far));
                return;
            }
        }
        releaseKeys(mc);
    }

    private static BlockPos findFarTarget(ClientWorld world, ClientPlayerEntity player, boolean haveSeeds) {
        BlockPos base = player.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SCAN_WALK; dx <= SCAN_WALK; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -SCAN_WALK; dz <= SCAN_WALK; dz++) {
                    BlockPos p = base.add(dx, dy, dz);
                    BlockState bs = world.getBlockState(p);
                    boolean ok = isMatureWheat(bs)
                        || (haveSeeds && bs.isAir() && world.getBlockState(p.down()).isOf(Blocks.FARMLAND));
                    if (!ok) continue;
                    double d = player.getPos().squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d < bestD) { bestD = d; best = p; }
                }
            }
        }
        return best;
    }

    // ---------------- SKRZYNIA ----------------
    private static void tickToChest(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world) {
        if (chestPos == null) {
            chestPos = findNearestChest(world, player);
            if (chestPos == null) {
                player.sendMessage(Text.literal("§c[AutoPszenica] Nie znaleziono wolnej skrzyni w pobliżu (" + SCAN_CHEST + " bloków). Farmię dalej, spróbuję za chwilę."), false);
                noChestUntil = tickCounter + 600; // 30 s
                state = State.FARM;
                return;
            }
        }
        Vec3d center = Vec3d.ofCenter(chestPos);
        if (player.getEyePos().distanceTo(center) <= REACH) {
            releaseKeys(mc);
            state = State.OPENING;
            timeout = 0;
            return;
        }
        if (++timeout > 20 * 20) { // 20 s na dojscie
            badChests.add(chestPos);
            chestPos = null;
            timeout = 0;
            releaseKeys(mc);
            return;
        }
        walkTo(mc, player, center);
    }

    private static void tickOpening(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world) {
        if (mc.currentScreen instanceof GenericContainerScreen<?>) {
            state = State.DEPOSITING;
            lastClickedSlot = -1;
            cooldown = 3;
            return;
        }
        if (timeout == 0 || timeout % 20 == 0) {
            Vec3d center = Vec3d.ofCenter(chestPos);
            lookAt(player, center);
            mc.interactionManager.interactBlock(player, Hand.MAIN_HAND,
                new BlockHitResult(center, Direction.UP, chestPos, false));
            player.swingHand(Hand.MAIN_HAND);
        }
        if (++timeout > 60) { // 3 s bez otwarcia -> ta skrzynia odpada
            badChests.add(chestPos);
            chestPos = null;
            timeout = 0;
            state = State.TO_CHEST;
        }
    }

    private static void tickDepositing(MinecraftClient mc, ClientPlayerEntity player, ClientWorld world) {
        if (!(player.currentScreenHandler instanceof GenericContainerScreenHandler handler)
                || !(mc.currentScreen instanceof GenericContainerScreen<?>)) {
            // okno zniknelo
            state = State.FARM;
            return;
        }
        int chestSlots = handler.getRows() * 9;

        // jesli ostatnio klikniety slot nadal ma pszenice -> skrzynia pelna
        if (lastClickedSlot != -1) {
            ItemStack still = handler.getSlot(lastClickedSlot).getStack();
            if (still.isOf(Items.WHEAT)) {
                player.sendMessage(Text.literal("§e[AutoPszenica] Skrzynia pełna, szukam następnej."), false);
                badChests.add(chestPos);
                chestPos = null;
                lastClickedSlot = -1;
                player.closeHandledScreen();
                state = State.TO_CHEST;
                timeout = 0;
                cooldown = 5;
                return;
            }
        }

        for (int i = chestSlots; i < handler.slots.size(); i++) {
            ItemStack st = handler.getSlot(i).getStack();
            if (st.isOf(Items.WHEAT)) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, player);
                lastClickedSlot = i;
                cooldown = 3;
                return;
            }
        }

        // cala pszenica odlozona
        player.closeHandledScreen();
        lastClickedSlot = -1;
        chestPos = null;
        state = State.FARM;
        cooldown = 10;
        player.sendMessage(Text.literal("§a[AutoPszenica] Odłożono pszenicę do skrzyni."), false);
    }

    private static BlockPos findNearestChest(ClientWorld world, ClientPlayerEntity player) {
        BlockPos base = player.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SCAN_CHEST; dx <= SCAN_CHEST; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -SCAN_CHEST; dz <= SCAN_CHEST; dz++) {
                    BlockPos p = base.add(dx, dy, dz);
                    if (badChests.contains(p)) continue;
                    Block b = world.getBlockState(p).getBlock();
                    if (!(b instanceof ChestBlock) && !(b instanceof BarrelBlock)) continue;
                    double d = player.getPos().squaredDistanceTo(Vec3d.ofCenter(p));
                    if (d < bestD) { bestD = d; best = p; }
                }
            }
        }
        return best;
    }

    // ================= narzedzia =================
    private static boolean isMatureWheat(BlockState bs) {
        return bs.isOf(Blocks.WHEAT) && ((CropBlock) bs.getBlock()).isMature(bs);
    }

    private static int countWheat(ClientPlayerEntity player) {
        int c = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isOf(Items.WHEAT)) c += s.getCount();
        }
        return c;
    }

    private static boolean hasEmptySlot(ClientPlayerEntity player) {
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getStack(i).isEmpty()) return true;
        }
        return false;
    }

    private static int findSeedSlot(ClientPlayerEntity player) {
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getStack(i).isOf(Items.WHEAT_SEEDS)) return i;
        }
        return -1;
    }

    /** Ustawia nasiona w rece (przenosi do hotbara jesli trzeba). */
    private static boolean selectSeeds(MinecraftClient mc, ClientPlayerEntity player) {
        int slot = findSeedSlot(player);
        if (slot == -1) return false;
        if (slot < 9) {
            player.getInventory().setSelectedSlot(slot);
            return true;
        }
        // z glownego ekwipunku -> zamiana z aktualnym slotem hotbara
        int hotbar = player.getInventory().getSelectedSlot();
        mc.interactionManager.clickSlot(player.playerScreenHandler.syncId, slot, hotbar, SlotActionType.SWAP, player);
        cooldown = 2;
        return false; // w nastepnym ticku nasiona beda juz w hotbarze
    }

    private static void lookAt(ClientPlayerEntity p, Vec3d target) {
        Vec3d eye = p.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    private static void walkTo(MinecraftClient mc, ClientPlayerEntity player, Vec3d target) {
        Vec3d pos = player.getPos();
        double dx = target.x - pos.x, dz = target.z - pos.z;
        player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        mc.options.forwardKey.setPressed(true);
        mc.options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround());
    }

    private static void releaseKeys(MinecraftClient mc) {
        if (mc == null || mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }
}
