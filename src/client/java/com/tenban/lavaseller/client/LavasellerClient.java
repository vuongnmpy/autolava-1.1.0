package com.tenban.lavaseller.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LavaSeller: tự múc lava, bán trên /ah, lấy xô rỗng từ /order.
 *
 * Chuẩn bị trước khi bật (K):
 *  - Ô hotbar 9: stack xô rỗng. Ô hotbar 1-8: để trống (để chứa xô lava).
 *  - Tay trái (offhand): đồ ăn (hoặc để trống, mod tự lấy đồ ăn từ túi lên tay trái).
 *  - Túi còn ít nhất 17 ô trống. Có block bắc cầu (netherrack, cobblestone...) nếu bật bắc cầu.
 *  - Phím: K bật/tắt, P mở menu, O bật/tắt bảng thống kê.
 */
public class LavasellerClient implements ClientModInitializer {
	// ===== HẰNG SỐ =====
	private static final double REACH = 4.4, MIN_SCOOP = 1.0;   // tầm múc lava
	private static final int AREA_Y = 60;                        // lệch độ cao tối đa so với chỗ bấm K
	private static final int SCAN_H = 24, SCAN_UP = 6, SCAN_DOWN = 14; // vùng quét lava quanh người
	private static final int NODE_CAP = 4000, MAX_PATH = 60, GOALS_WANTED = 8;
	private static final long PLAN_BUDGET_NS = 30_000_000L;      // tối đa 30ms mỗi lần lập kế hoạch
	private static final int EAT_BELOW = 12, HUNGER_STOP = 6;    // ăn khi đói <= 12, dừng nếu <= 6 mà hết đồ ăn
	private static final float HP_STOP = 8f;                     // dừng khi máu <= 8 (4 tim)
	private static final int SELL_MIN = 8, SELL_VAR = 10;        // mỗi xô cách nhau khoảng 0,5 - 1,0 giây
	private static final int PENDING_TICKS = 50;
	private static final int PROBE_MINUTES = 5;                  // bị chặn quá N phút không có dòng xanh -> thử 1 lệnh
	private static final int CMD_GAP = 40, CMD_JITTER = 20;      // giãn cách lệnh /order
	private static final int MAX_BRIDGE_RUN = 6, MAX_BRIDGE_TOTAL = 24;
	private static final int ALERT_TICKS = 1200, CHECK_TICKS = 400; // chữ đỏ mờ sau 60 giây
	private static final float TURN_SPEED = 12f;
	private static final int MAX_SOFT = 4;                       // số lần tự thử lại trước khi dừng hẳn

	// ===== CÀI ĐẶT (chỉnh trong menu, lưu file) =====
	private static boolean optBridge = true, optOrder = true, optBackground = true, optEat = true, optAvoidSoul = true;
	private static int optEscape = 50, optBatch = 16, optStacks = 5, optRadius = 100;

	// block dùng để bắc cầu (không dùng cát/sỏi vì rơi)
	private static final Item[] BRIDGE_ITEMS = {
			Items.NETHERRACK, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.BLACKSTONE, Items.STONE, Items.DIRT
	};
	// đồ ăn không tự lấy từ túi lên tay trái
	private static final Item[] BAD_FOOD = {
			Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.PUFFERFISH, Items.POISONOUS_POTATO, Items.CHICKEN,
			Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE
	};

	private static final int PH_PLAN = 1, PH_WALK = 2, PH_BRIDGE = 3, PH_BLOCKSWAP = 4, PH_SCOOP = 5,
			PH_SELL = 6, PH_SPREAD = 7, PH_BUCKET = 8, PH_EAT = 9, PH_FOODSWAP = 10, PH_WAIT = 11,
			PH_R_CMD = 12, PH_R_PAGE = 13, PH_R_LIST = 14, PH_R_EDIT = 15, PH_R_COLLECT = 16;
	private static final int A_NONE = 0, A_ERROR = 1, A_CHECK = 2, A_INFO = 3;
	private static final Pattern MONEY = Pattern.compile("\\$\\s*([0-9][0-9,]*\\.?[0-9]*)([kmb]?)");

	private static class Plan {
		List<BlockPos> path = new ArrayList<>();
		BlockPos goal = null;
		Set<Long> bridges = new HashSet<>();
		Set<Long> edges = new HashSet<>();
	}

	private static class Goal {
		BlockPos src;
		double score;
	}

	private KeyMapping keyToggle, keyMenu, keyPanel;
	private String priceText = "";
	private long priceValue = 0;
	private boolean running = false, blocked = false, orderTest = false;
	private int phase = 0, cooldown = 0, timer = 0, menuAge = 0, noProgress = 0, lastCount = -1;
	private long tickNow = 0, runTicks = 0, blockedSince = 0, lastCmdTick = -1000, checkArmedUntil = 0;
	private int cmdGap = CMD_GAP;
	private float healthAnchor = 20f;
	private long healthAnchorTick = 0;
	private Boolean savedPause = null;
	private String lastStopReason = "";

	// cảnh báo trên màn hình
	private int alertKind = A_NONE;
	private String alertText = "";
	private long alertTick = 0, alertDur = 0;

	// bán
	private final long[] pendingUntil = new long[9];
	private final int[] sellTries = new int[9];
	private final boolean[] sellSent = new boolean[9];

	// di chuyển
	private final Set<Long> blacklist = new HashSet<>();
	private final Set<Long> visitedCoarse = new HashSet<>();
	private final Set<Long> bridgeSet = new HashSet<>();
	private final Set<Long> edgeCells = new HashSet<>();
	private final Set<Long> badCells = new HashSet<>();
	private final HashMap<Long, Boolean> cStrict = new HashMap<>();
	private final HashMap<Long, Boolean> cEdge = new HashMap<>();
	private BlockPos home = BlockPos.ZERO;
	private List<BlockPos> path = new ArrayList<>();
	private int pathIdx = 0, replans = 0, stepTimer = 0, planRounds = 0, planStage = 0, stuckTicks = 0, stuckLevel = 0;
	private long strafeUntil = 0, jumpUntil = 0;
	private int strafeDir = 1;
	private Vec3 lastPos = Vec3.ZERO;
	private BlockPos goalSrc = null;
	private int scoopWait = 0, scoopFail = 0, scoopTimer = 0, lavaBefore = 0;
	private int bucketsBefore = 0, orderTaken = 0, orderAttempts = 0, softCount = 0;
	private long waitUntil = 0, eatBanUntil = 0;
	private String waitReason = "";
	private int eatStage = 0, eatTimer = 0;

	// bắc cầu
	private long bridgeBanUntil = 0;
	private BlockPos bridgeFrom = null, bridgeTarget = null;
	private int bridgeStage = 0, bridgeTimer = 0, bridgeTries = 0, bridgeWait = 0;

	// thống kê
	private int sScoop = 0, sSold = 0, sBridge = 0, dSold = 0;
	private long sMoney = 0, dMoney = 0;
	private String statDate = "";
	private boolean dirty = false, showPanel = true;

	@Override
	public void onInitializeClient() {
		loadSettings();
		keyToggle = reg("key.lavaseller.toggle", GLFW.GLFW_KEY_K);
		keyMenu = reg("key.lavaseller.menu", GLFW.GLFW_KEY_P);
		keyPanel = reg("key.lavaseller.panel", GLFW.GLFW_KEY_O);
		loadStats();

		// Chỉ đọc tin nhắn hệ thống (GAME), không đọc chat của người chơi
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) onMessage(message);
		});
		HudRenderCallback.EVENT.register((g, delta) -> {
			drawPanel(g);
			drawAlert(g);
		});
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private static KeyMapping reg(String name, int key) {
		return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM, key, "key.categories.misc"));
	}

	// ===================== VÒNG LẶP CHÍNH =====================

	private void onTick(Minecraft mc) {
		if (mc.getConnection() == null) { // mất kết nối: reset giá, lưu thống kê
			priceText = "";
			priceValue = 0;
			running = false;
			alertKind = A_NONE;
			if (dirty) saveStats();
			return;
		}
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || mc.gameMode == null) { running = false; return; }
		tickNow++;
		if (tickNow % 20 == 0) rollover();
		if (dirty && tickNow % 100 == 0) saveStats();
		handleKeys(mc, p);
		if (!running) return;
		runTicks++;
		if (optBackground) keepAlive(mc);

		// --- thoát khẩn cấp: có người chơi khác lại gần ---
		for (var other : mc.level.players()) {
			if (other == p) continue;
			if (other.distanceTo(p) <= optEscape) {
				halt(mc);
				saveStats();
				mc.getConnection().getConnection().disconnect(
						Component.literal("[LavaSeller] Co nguoi gan: " + other.getName().getString()));
				return;
			}
		}

		// --- an toàn cơ thể ---
		if (p.isDeadOrDying()) { halt(mc); return; }
		if (tickNow - healthAnchorTick >= 40) { healthAnchor = p.getHealth(); healthAnchorTick = tickNow; }
		if (p.getHealth() <= HP_STOP) { stop(p, "Mau thap (con " + (int) p.getHealth() + ")."); return; }
		if (healthAnchor - p.getHealth() >= 6f) { stop(p, "Mat mau qua nhanh."); return; }
		if (p.isOnFire() || p.isInLava()) { stop(p, "Boc chay hoac dang o trong lava!"); return; }
		if (p.getFoodData().getFoodLevel() <= HUNGER_STOP && !(optEat && foodAvailable(p))) {
			stop(p, "Doi bung va khong con do an.");
			return;
		}
		boolean outside = phase == PH_PLAN || phase == PH_WALK || phase == PH_BRIDGE || phase == PH_SCOOP || phase == PH_WAIT;
		if (outside && p.fallDistance > 2.5) { stop(p, "Dang roi tu cho cao, dung de an toan."); return; }
		if (outside && lavaDanger(mc, p)) { stop(p, "Lava sat nguoi (cung do cao), dung de an toan."); return; }
		if (phase != PH_WALK && phase != PH_BRIDGE) {
			releaseKeys(mc);
			if (mc.screen == null && lavaWithin(mc, p, 2)) mc.options.keyShift.setDown(true); // gần lava thì luôn cúi
		}
		if (cooldown > 0) { cooldown--; return; }

		switch (phase) {
			case PH_PLAN -> doPlan(mc, p);
			case PH_WALK -> doWalk(mc, p);
			case PH_BRIDGE -> doBridge(mc, p);
			case PH_BLOCKSWAP -> doBlockSwap(mc, p);
			case PH_SCOOP -> doScoop(mc, p);
			case PH_SELL -> doSell(mc, p);
			case PH_SPREAD -> doSpread(mc, p);
			case PH_BUCKET -> doBucket(mc, p);
			case PH_EAT -> doEat(mc, p);
			case PH_FOODSWAP -> doFoodSwap(mc, p);
			case PH_WAIT -> { if (tickNow >= waitUntil) { setPhase(PH_PLAN); cooldown = 10; } }
			case PH_R_CMD -> {
				if (tickNow - lastCmdTick >= cmdGap) {
					bucketsBefore = countItem(p, Items.BUCKET);
					orderTaken = 0;
					mc.getConnection().sendCommand("order");
					lastCmdTick = tickNow;
					cmdGap = CMD_GAP + p.getRandom().nextInt(CMD_JITTER + 1);
					setPhase(PH_R_PAGE);
				}
			}
			case PH_R_PAGE -> doOrderPage(mc, p);
			case PH_R_LIST -> doOrderList(mc, p);
			case PH_R_EDIT -> doOrderEdit(mc, p);
			case PH_R_COLLECT -> doOrderCollect(mc, p);
			default -> { }
		}
	}

	// Chạy nền: không tự mở menu tạm dừng khi mất focus cửa sổ
	private void keepAlive(Minecraft mc) {
		if (savedPause == null) savedPause = mc.options.pauseOnLostFocus;
		mc.options.pauseOnLostFocus = false;
		if (!mc.isWindowActive() && mc.screen instanceof PauseScreen) mc.setScreen(null);
	}

	// ===================== PHÍM, KHỞI ĐỘNG, DỪNG =====================

	private void handleKeys(Minecraft mc, LocalPlayer p) {
		while (keyPanel.consumeClick()) showPanel = !showPanel;
		while (keyMenu.consumeClick()) mc.setScreen(new MenuScreen(this));
		while (keyToggle.consumeClick()) {
			if (running) { userStop(mc); continue; }
			if (alertKind == A_ERROR && alertActive()) { alertKind = A_NONE; continue; } // K tắt chữ đỏ ngay
			requestStart(mc, p);
		}
	}

	private void toggleFromGui() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null) return;
		if (running) userStop(mc);
		else requestStart(mc, p);
	}

	private void testOrderFromGui() {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p == null || running) return;
		startRun(mc, p, true);
	}

	private void requestStart(Minecraft mc, LocalPlayer p) {
		if (priceText.isEmpty()) { alert(A_ERROR, "Chua nhap gia ban. Bam P de mo menu va nhap gia."); return; }
		if (lavaDanger(mc, p)) { alert(A_ERROR, "Dang dung sat lava. Hay di ra xa roi bam K."); return; }
		List<String> w = preflight(p);
		if (!w.isEmpty() && checkArmedUntil <= tickNow) {
			checkArmedUntil = tickNow + CHECK_TICKS;
			alert(A_CHECK, "Con thieu:\n- " + String.join("\n- ", w) + "\nBam K lan nua de van chay");
			return;
		}
		checkArmedUntil = 0;
		startRun(mc, p, false);
	}

	private List<String> preflight(LocalPlayer p) {
		List<String> w = new ArrayList<>();
		if (optEat) {
			ItemStack off = p.getOffhandItem();
			if (off.isEmpty()) {
				if (findFoodSlot(p) < 0) w.add("Khong co do an (de o tay trai)");
			} else if (!isFood(off)) {
				w.add("Tay trai dang cam vat khac, khong tu an duoc");
			}
		}
		for (int i = 0; i < 8; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (!s.isEmpty() && !s.is(Items.LAVA_BUCKET) && !isBridgeItem(s)) { w.add("O hotbar 1-8 chua trong"); break; }
		}
		if (emptySlots(p) < optBatch + 1) w.add("Tui can it nhat " + (optBatch + 1) + " o trong");
		if (countItem(p, Items.BUCKET) == 0 && countItem(p, Items.LAVA_BUCKET) == 0 && !optOrder) w.add("Khong co xo rong");
		if (optBridge && countBridge(p) == 0) w.add("Khong co block bac cau (netherrack, cobblestone...)");
		return w;
	}

	private void startRun(Minecraft mc, LocalPlayer p, boolean test) {
		running = true;
		blocked = false;
		orderTest = test;
		home = p.blockPosition();
		blacklist.clear();
		visitedCoarse.clear();
		bridgeSet.clear();
		edgeCells.clear();
		badCells.clear();
		Arrays.fill(pendingUntil, 0);
		Arrays.fill(sellTries, 0);
		Arrays.fill(sellSent, false);
		sScoop = 0;
		sSold = 0;
		sBridge = 0;
		sMoney = 0;
		planRounds = 0;
		planStage = 0;
		softCount = 0;
		orderAttempts = 0;
		eatBanUntil = 0;
		bridgeBanUntil = 0;
		healthAnchor = p.getHealth();
		healthAnchorTick = tickNow;
		lastStopReason = "";
		alertKind = A_NONE;
		setPhase(test ? PH_R_CMD : PH_PLAN);
		cooldown = 10 + p.getRandom().nextInt(10);
	}

	private void softWait(LocalPlayer p, String reason, int seconds) {
		if (++softCount > MAX_SOFT) { stop(p, reason + " (da thu lai " + MAX_SOFT + " lan)."); return; }
		waitReason = reason;
		waitUntil = tickNow + seconds * 20L;
		releaseKeys(Minecraft.getInstance());
		setPhase(PH_WAIT);
	}

	private void halt(Minecraft mc) {
		running = false;
		phase = 0;
		releaseKeys(mc);
		if (savedPause != null) { // trả lại cài đặt tạm dừng khi mất focus như ban đầu
			mc.options.pauseOnLostFocus = savedPause;
			savedPause = null;
		}
	}

	private void userStop(Minecraft mc) {
		halt(mc);
		lastStopReason = "Nguoi dung tat";
		alertKind = A_NONE;
		saveStats();
	}

	private void stop(LocalPlayer p, String reason) {
		Minecraft mc = Minecraft.getInstance();
		halt(mc);
		if (mc.screen instanceof AbstractContainerScreen<?>) p.closeContainer();
		lastStopReason = reason;
		alert(A_ERROR, "LAVASELLER DUNG: " + reason);
		saveStats();
	}

	private void finishInfo(LocalPlayer p, String msg) {
		Minecraft mc = Minecraft.getInstance();
		halt(mc);
		if (mc.screen instanceof AbstractContainerScreen<?>) p.closeContainer();
		lastStopReason = msg;
		alert(A_INFO, msg);
	}

	// ===================== LẬP KẾ HOẠCH =====================

	private void doPlan(Minecraft mc, LocalPlayer p) {
		int lava = countItem(p, Items.LAVA_BUCKET), buckets = countItem(p, Items.BUCKET);
		if (lava >= optBatch || (buckets == 0 && lava > 0) || (lava > 0 && emptySlots(p) == 0)) { setPhase(PH_SELL); return; }
		if (buckets == 0) {
			if (!optOrder) { stop(p, "Het xo rong (dang tat lay xo tu /order)."); return; }
			orderAttempts = 0;
			setPhase(PH_R_CMD);
			return;
		}
		if (emptySlots(p) == 0) { stop(p, "Tui day, khong con cho chua xo lava."); return; }
		if (optEat && tickNow >= eatBanUntil && p.getFoodData().getFoodLevel() <= EAT_BELOW) {
			eatStage = 0;
			setPhase(PH_EAT);
			return;
		}
		if (!p.getInventory().getItem(8).is(Items.BUCKET)) { setPhase(PH_BUCKET); return; }
		if (p.getInventory().selected != 8) { p.getInventory().selected = 8; cooldown = 3; return; }

		BlockPos s = findScoopable(mc, p);
		if (s != null) { goalSrc = s; planStage = 0; setPhase(PH_SCOOP); return; }
		if (++planRounds > 40) { planRounds = 0; softWait(p, "Khong tim thay lava", 30); return; }

		boolean canBridge = optBridge && tickNow >= bridgeBanUntil && countBridge(p) > 0;
		cooldown = 2; // chia nhỏ việc nặng ra nhiều tick
		switch (planStage) {
			case 0 -> {
				Plan pl = plan(mc, p, false, false);
				if (pl == null) { softWait(p, "Cho dang dung khong an toan", 20); return; }
				if (pl.goal != null) {
					if (pl.path.isEmpty()) { blacklist.add(pl.goal.asLong()); return; }
					startPath(pl);
					return;
				}
				planStage = canBridge ? 1 : 2;
			}
			case 1 -> {
				Plan pb = plan(mc, p, false, true);
				if (pb != null && pb.goal != null && !pb.path.isEmpty()) { startPath(pb); return; }
				planStage = 2;
			}
			case 2 -> {
				Plan ex = plan(mc, p, true, false);
				if (ex != null && !ex.path.isEmpty()) { startPath(ex); return; }
				if (canBridge) { planStage = 3; return; }
				planStage = 0;
				softWait(p, "Khong tim thay lava va het duong di an toan", 30);
			}
			default -> {
				Plan ex = plan(mc, p, true, true);
				planStage = 0;
				if (ex != null && !ex.path.isEmpty()) { startPath(ex); return; }
				softWait(p, "Khong tim thay lava va het duong di an toan", 30);
			}
		}
	}

	private void startPath(Plan pl) {
		path = pl.path;
		bridgeSet.clear();
		bridgeSet.addAll(pl.bridges);
		edgeCells.clear();
		edgeCells.addAll(pl.edges);
		pathIdx = 0;
		replans = 0;
		stepTimer = 0;
		stuckTicks = 0;
		stuckLevel = 0;
		goalSrc = pl.goal;
		planStage = 0;
		lastPos = Vec3.ZERO;
		setPhase(PH_WALK);
	}

	private boolean strictAt(ClientLevel lv, BlockPos c) {
		long k = c.asLong();
		if (badCells.contains(k)) return false;
		Boolean v = cStrict.get(k);
		if (v == null) { v = safeCell(lv, c); cStrict.put(k, v); }
		return v;
	}

	private boolean edgeAt(ClientLevel lv, BlockPos c) {
		long k = c.asLong();
		if (badCells.contains(k)) return false;
		Boolean v = cEdge.get(k);
		if (v == null) { v = edgeOk(lv, c); cEdge.put(k, v); }
		return v;
	}

	private boolean stepAt(ClientLevel lv, BlockPos a, BlockPos b, int dy, boolean edge) {
		boolean ok = edge ? (edgeAt(lv, b) && !strictAt(lv, b)) : strictAt(lv, b);
		if (!ok) return false;
		if (dy == 1) return passable(lv, a.above(2));
		if (dy == -1) return passable(lv, b.above(2));
		return true;
	}

	private static long bucketKey(BlockPos pos) {
		return BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
	}

	// Chấm điểm một ô đứng: càng nhiều lava gần, đường càng ngắn, sàn ngang bề mặt lava càng tốt
	private Goal evalGoal(BlockPos c, HashMap<Long, List<BlockPos>> buckets, int d, boolean edge) {
		double eyeH = edge ? 1.27 : 1.62;
		int bx = c.getX() >> 2, by = c.getY() >> 2, bz = c.getZ() >> 2;
		int density = 0;
		boolean level = false, adj = false;
		double bestKey = 1e9;
		BlockPos bs = null;
		for (int ix = -1; ix <= 1; ix++)
			for (int iy = -1; iy <= 1; iy++)
				for (int iz = -1; iz <= 1; iz++) {
					List<BlockPos> l = buckets.get(BlockPos.asLong(bx + ix, by + iy, bz + iz));
					if (l == null) continue;
					for (BlockPos s : l) {
						int dx = s.getX() - c.getX(), dz = s.getZ() - c.getZ();
						double vy = s.getY() + 0.5 - (c.getY() + eyeH);
						double dd = Math.sqrt(dx * dx + vy * vy + dz * dz);
						if (dd <= 6.0) density++;
						if (dd >= MIN_SCOOP && dd <= REACH - 0.2) {
							boolean same = s.getY() == c.getY() - 1; // sàn ngang bề mặt lava
							double key = dd - (same ? 1.5 : 0);
							if (key < bestKey) { bestKey = key; bs = s; }
							if (same) {
								level = true;
								if (Math.abs(dx) + Math.abs(dz) == 1) adj = true;
							}
						}
					}
				}
		if (bs == null) return null;
		Goal g = new Goal();
		g.src = bs;
		g.score = density - d * 0.6 + (level ? 25 : 0) + (adj ? 10 : 0);
		return g;
	}

	private Plan plan(Minecraft mc, LocalPlayer p, boolean explore, boolean bridge) {
		ClientLevel lv = mc.level;
		long deadline = System.nanoTime() + PLAN_BUDGET_NS;
		cStrict.clear();
		cEdge.clear();
		BlockPos start = p.blockPosition();
		if (!safeCell(lv, start) && !edgeOk(lv, start)) return null;
		visitedCoarse.add(coarse(start));
		int budget = bridge ? Math.min(MAX_BRIDGE_TOTAL, countBridge(p)) : 0;

		HashMap<Long, List<BlockPos>> buckets = new HashMap<>();
		int nsrc = 0;
		if (!explore) {
			for (int x = -SCAN_H; x <= SCAN_H; x++)
				for (int z = -SCAN_H; z <= SCAN_H; z++)
					for (int y = -SCAN_DOWN; y <= SCAN_UP; y++) {
						BlockPos pos = start.offset(x, y, z);
						if (!lv.getFluidState(pos).is(FluidTags.LAVA)) continue;
						if (!isTopSource(lv, pos) || blacklist.contains(pos.asLong())) continue;
						buckets.computeIfAbsent(bucketKey(pos), k -> new ArrayList<>()).add(pos);
						nsrc++;
					}
		}

		ArrayDeque<BlockPos> q = new ArrayDeque<>();
		HashMap<Long, Long> parent = new HashMap<>();
		HashMap<Long, Integer> dist = new HashMap<>();
		HashMap<Long, Integer> run = new HashMap<>();
		HashMap<Long, Integer> used = new HashMap<>();
		Set<Long> bridged = new HashSet<>();
		Set<Long> edges = new HashSet<>();
		long sk = start.asLong();
		q.add(start);
		parent.put(sk, sk);
		dist.put(sk, 0);
		run.put(sk, 0);
		used.put(sk, 0);
		BlockPos bestCell = null, bestSrc = null;
		double bestScore = -1e9;
		int goals = 0, nodes = 0;
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		int[] dys = {0, 1, -1};
		boolean startEdge = !safeCell(lv, start);

		while (!q.isEmpty() && nodes < NODE_CAP) {
			if ((nodes & 63) == 0 && System.nanoTime() > deadline) break;
			BlockPos c = q.poll();
			nodes++;
			long ck = c.asLong();
			int d = dist.get(ck);
			if (!explore && nsrc > 0) {
				Goal g = evalGoal(c, buckets, d, ck == sk ? startEdge : false);
				if (g != null) {
					goals++;
					if (g.score > bestScore) { bestScore = g.score; bestCell = c; bestSrc = g.src; }
					if (goals >= GOALS_WANTED) break;
				}
			}
			if (d >= MAX_PATH) continue;
			for (int[] dr : dirs) {
				boolean moved = false;
				for (int dy : dys) {
					BlockPos n = c.offset(dr[0], dy, dr[1]);
					long nk = n.asLong();
					if (parent.containsKey(nk) || !inArea(n) || !stepAt(lv, c, n, dy, false)) continue;
					parent.put(nk, ck);
					dist.put(nk, d + 1);
					run.put(nk, 0);
					used.put(nk, used.get(ck));
					q.add(n);
					moved = true;
					break;
				}
				if (moved) continue;

				// ô sát mép lava (chỉ làm điểm đến, không đi tiếp qua đó)
				if (!explore && nsrc > 0) {
					for (int dy : dys) {
						BlockPos n = c.offset(dr[0], dy, dr[1]);
						long nk = n.asLong();
						if (parent.containsKey(nk) || !inArea(n) || !stepAt(lv, c, n, dy, true)) continue;
						parent.put(nk, ck);
						dist.put(nk, d + 1);
						edges.add(nk);
						Goal g = evalGoal(n, buckets, d + 1, true);
						if (g != null) {
							goals++;
							if (g.score > bestScore) { bestScore = g.score; bestCell = n; bestSrc = g.src; }
						}
						moved = true;
						break;
					}
					if (moved) continue;
				}

				// bắc cầu một ô cùng độ cao
				if (budget > 0) {
					BlockPos n = c.offset(dr[0], 0, dr[1]);
					long nk = n.asLong();
					int r = run.get(ck), u = used.get(ck);
					if (!parent.containsKey(nk) && inArea(n) && r < MAX_BRIDGE_RUN && u < budget && bridgeCell(lv, n)) {
						parent.put(nk, ck);
						dist.put(nk, d + 1);
						run.put(nk, r + 1);
						used.put(nk, u + 1);
						bridged.add(nk);
						q.add(n);
					}
				}
			}
		}

		Plan pl = new Plan();
		if (explore) {
			double best = -1;
			for (var e : dist.entrySet()) {
				int d = e.getValue();
				if (d < 6 || edges.contains(e.getKey())) continue;
				BlockPos pos = BlockPos.of(e.getKey());
				double score = (visitedCoarse.contains(coarse(pos)) ? 0 : 200) + d;
				if (score > best) { best = score; bestCell = pos; }
			}
		}
		if (bestCell != null) {
			long k = bestCell.asLong();
			while (k != sk) {
				pl.path.add(BlockPos.of(k));
				if (bridged.contains(k)) pl.bridges.add(k);
				if (edges.contains(k)) pl.edges.add(k);
				k = parent.get(k);
			}
			Collections.reverse(pl.path);
			pl.goal = explore ? null : bestSrc;
		}
		return pl;
	}

	private long coarse(BlockPos c) {
		return BlockPos.asLong(c.getX() >> 3, c.getY() >> 3, c.getZ() >> 3);
	}

	private boolean inArea(BlockPos b) {
		return Math.abs(b.getX() - home.getX()) <= optRadius && Math.abs(b.getZ() - home.getZ()) <= optRadius
				&& Math.abs(b.getY() - home.getY()) <= AREA_Y;
	}

	// ===================== AN TOÀN KHI ĐI =====================

	private static boolean hazard(BlockState s) {
		if (optAvoidSoul && s.is(BlockTags.SOUL_SPEED_BLOCKS)) return true; // cát linh hồn, đất linh hồn
		return s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH)
				|| s.is(Blocks.WITHER_ROSE) || s.is(Blocks.COBWEB) || s.is(Blocks.POINTED_DRIPSTONE)
				|| s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES);
	}

	private static boolean isLava(ClientLevel lv, BlockPos pos) {
		return lv.getFluidState(pos).is(FluidTags.LAVA);
	}

	private static boolean passable(ClientLevel lv, BlockPos pos) {
		BlockState s = lv.getBlockState(pos);
		return s.getCollisionShape(lv, pos).isEmpty() && s.getFluidState().isEmpty() && !hazard(s);
	}

	private static boolean solidFloor(ClientLevel lv, BlockPos c) {
		BlockPos fl = c.below();
		BlockState f = lv.getBlockState(fl);
		return !f.getCollisionShape(lv, fl).isEmpty() && f.getFluidState().isEmpty();
	}

	// Ô đi lại an toàn: sàn đặc, chân và đầu thoáng, không có lava trong 1 block quanh
	private static boolean safeCell(ClientLevel lv, BlockPos c) {
		if (!lv.hasChunkAt(c)) return false;
		BlockPos fl = c.below();
		BlockState f = lv.getBlockState(fl);
		if (f.getCollisionShape(lv, fl).isEmpty() || !f.getFluidState().isEmpty() || hazard(f)) return false;
		if (!passable(lv, c) || !passable(lv, c.above())) return false;
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				for (int dy = -1; dy <= 2; dy++)
					if (isLava(lv, c.offset(dx, dy, dz))) return false;
		return true;
	}

	// Ô sát mép lava: chỉ cho lava nằm cùng tầng với sàn (dy = -1), không cho lava ngang chân hoặc cao hơn
	private static boolean edgeOk(ClientLevel lv, BlockPos c) {
		if (!lv.hasChunkAt(c)) return false;
		BlockPos fl = c.below();
		BlockState f = lv.getBlockState(fl);
		if (f.getCollisionShape(lv, fl).isEmpty() || !f.getFluidState().isEmpty() || hazard(f)) return false;
		if (!passable(lv, c) || !passable(lv, c.above())) return false;
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				for (int dy = 0; dy <= 2; dy++)
					if (isLava(lv, c.offset(dx, dy, dz))) return false;
		return true;
	}

	// Ô có thể bắc cầu: sàn là không khí, chân/đầu thoáng, không có lava quanh và không có lava trong 4 block bên dưới
	private static boolean bridgeCell(ClientLevel lv, BlockPos c) {
		if (!lv.hasChunkAt(c)) return false;
		BlockState f = lv.getBlockState(c.below());
		if (!f.isAir() || !f.getFluidState().isEmpty()) return false;
		if (!passable(lv, c) || !passable(lv, c.above())) return false;
		for (int dx = -1; dx <= 1; dx++)
			for (int dz = -1; dz <= 1; dz++)
				for (int dy = -1; dy <= 2; dy++)
					if (isLava(lv, c.offset(dx, dy, dz))) return false;
		for (int dy = -2; dy >= -4; dy--)
			if (isLava(lv, c.offset(0, dy, 0))) return false;
		return true;
	}

	private boolean lavaWithin(Minecraft mc, LocalPlayer p, int r) {
		BlockPos c = p.blockPosition();
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++)
				for (int dy = -1; dy <= 1; dy++)
					if (isLava(mc.level, c.offset(dx, dy, dz))) return true;
		return false;
	}

	// Nguy hiểm thật: lava ở ô chân/đầu hoặc ngay cạnh ở độ cao chân/đầu (lava ngang sàn ở dưới thì không tính)
	private boolean lavaDanger(Minecraft mc, LocalPlayer p) {
		BlockPos c = p.blockPosition();
		ClientLevel lv = mc.level;
		for (int dy = 0; dy <= 1; dy++) {
			BlockPos b = c.above(dy);
			if (isLava(lv, b) || isLava(lv, b.east()) || isLava(lv, b.west()) || isLava(lv, b.north()) || isLava(lv, b.south())) {
				return true;
			}
		}
		return false;
	}

	// Có ô liền kề không có sàn (nguy cơ rơi)?
	private static boolean nearDrop(ClientLevel lv, BlockPos c) {
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : dirs) {
			BlockPos n = c.offset(d[0], 0, d[1]);
			if (!passable(lv, n)) continue;
			BlockPos fl = n.below();
			BlockState f = lv.getBlockState(fl);
			if (f.getCollisionShape(lv, fl).isEmpty()) return true;
		}
		return false;
	}

	// ===================== ĐI =====================

	private void doWalk(Minecraft mc, LocalPlayer p) {
		ClientLevel lv = mc.level;
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		if (pathIdx >= path.size()) { releaseKeys(mc); setPhase(PH_PLAN); cooldown = 3; return; }
		BlockPos nxt = path.get(pathIdx), cur = p.blockPosition();
		double hd = Math.hypot(p.getX() - (nxt.getX() + 0.5), p.getZ() - (nxt.getZ() + 0.5));
		if (cur.getX() == nxt.getX() && cur.getZ() == nxt.getZ() && hd < 0.3) {
			pathIdx++;
			stepTimer = 0;
			stuckTicks = 0;
			stuckLevel = 0;
			return;
		}
		int dy = nxt.getY() - cur.getY();
		boolean adj = Math.abs(nxt.getX() - cur.getX()) + Math.abs(nxt.getZ() - cur.getZ()) <= 1;
		long nk = nxt.asLong();
		boolean isEdge = edgeCells.contains(nk);

		// ô tiếp theo cần đặt block làm sàn -> vào chế độ bắc cầu
		if (bridgeSet.contains(nk) && !solidFloor(lv, nxt)) {
			if (!adj || dy != 0 || dirOf(cur, nxt) == null) { replan(mc); return; }
			releaseKeys(mc);
			if (findBridgeHotbar(p) < 0) {
				if (countBridge(p) == 0) { stop(p, "Het block de bac cau."); return; }
				setPhase(PH_BLOCKSWAP);
				return;
			}
			bridgeFrom = cur;
			bridgeTarget = nxt;
			bridgeStage = 0;
			bridgeTimer = 0;
			bridgeTries = 0;
			bridgeWait = 0;
			setPhase(PH_BRIDGE);
			return;
		}

		boolean ok = isEdge ? edgeOk(lv, nxt) : safeCell(lv, nxt);
		if (!adj || Math.abs(dy) > 1 || !ok) { replan(mc); return; }

		float yaw = (float) Math.toDegrees(Math.atan2(nxt.getZ() + 0.5 - p.getZ(), nxt.getX() + 0.5 - p.getX())) - 90f;
		turnTo(p, yaw, 0f);
		boolean aligned = Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 25f;
		boolean lava2 = lavaWithin(mc, p, 2);
		mc.options.keyUp.setDown(aligned);
		boolean jump = dy > 0 || tickNow < jumpUntil || (p.horizontalCollision && p.onGround());
		if (lava2 && dy <= 0) jump = false; // gần lava thì không nhảy
		mc.options.keyJump.setDown(jump);
		mc.options.keyShift.setDown(isEdge || lava2 || lavaWithin(mc, p, 3) || nearDrop(lv, cur));
		if (tickNow < strafeUntil) {
			if (strafeDir > 0) mc.options.keyRight.setDown(true);
			else mc.options.keyLeft.setDown(true);
		}

		// chống kẹt: đứng yên quá lâu thì nhảy, né sang bên, bỏ ô, lập kế hoạch lại
		double mv = p.position().distanceToSqr(lastPos);
		lastPos = p.position();
		if (mv < 0.0004) stuckTicks++;
		else stuckTicks = 0;
		if (stuckTicks > 30) {
			stuckTicks = 0;
			onStuck(mc, p, nxt, lava2);
			return;
		}
		if (++stepTimer > 160) replan(mc);
	}

	private void onStuck(Minecraft mc, LocalPlayer p, BlockPos nxt, boolean lava2) {
		stuckLevel++;
		strafeDir = p.getRandom().nextBoolean() ? 1 : -1;
		if (stuckLevel == 1) {
			if (!lava2) jumpUntil = tickNow + 8;
			else strafeUntil = tickNow + 12;
		} else if (stuckLevel == 2) {
			strafeUntil = tickNow + 12;
		} else {
			badCells.add(nxt.asLong());
			stuckLevel = 0;
			if (badCells.size() >= 6) {
				badCells.clear();
				softWait(p, "Bi ket nhieu lan", 30);
			} else {
				replan(mc);
			}
		}
	}

	private void replan(Minecraft mc) {
		if (++replans > 4) {
			if (goalSrc != null) blacklist.add(goalSrc.asLong());
			replans = 0;
		}
		releaseKeys(mc);
		setPhase(PH_PLAN);
		cooldown = 5;
	}

	// ===================== BẮC CẦU: tự đặt block =====================

	// Cúi để không rơi, quay lưng về phía đi, nhìn xuống mép block đang đứng,
	// lùi ra mép rồi đặt vào mặt bên của block đang đứng. Xong thì lùi lên block mới.
	private void doBridge(Minecraft mc, LocalPlayer p) {
		if (bridgeTarget == null || bridgeFrom == null) { setPhase(PH_PLAN); return; }
		ClientLevel lv = mc.level;
		Direction dir = dirOf(bridgeFrom, bridgeTarget);

		if (bridgeStage == 2) { // thất bại: bước về phía ô an toàn rồi lập kế hoạch lại
			if (dir != null) turnTo(p, yawOf(dir) + 180f, 80f);
			mc.options.keyShift.setDown(true);
			mc.options.keyDown.setDown(false);
			mc.options.keyUp.setDown(true);
			if (++bridgeTimer > 12) {
				releaseKeys(mc);
				replans = 0;
				setPhase(PH_PLAN);
				cooldown = 6;
			}
			return;
		}

		BlockPos cur = p.blockPosition();
		if (dir == null || (!cur.equals(bridgeFrom) && !cur.equals(bridgeTarget))) { bridgeAbort(mc, false); return; }
		mc.options.keyShift.setDown(true);
		if (++bridgeTimer > 240) { bridgeAbort(mc, true); return; }

		int hb = findBridgeHotbar(p);
		if (hb < 0) {
			releaseKeys(mc);
			if (countBridge(p) > 0) { setPhase(PH_BLOCKSWAP); return; }
			stop(p, "Het block de bac cau.");
			return;
		}
		if (p.getInventory().selected != hb) { p.getInventory().selected = hb; cooldown = 3; return; }
		if (!p.isShiftKeyDown()) { // chờ vào trạng thái cúi
			mc.options.keyUp.setDown(false);
			mc.options.keyDown.setDown(false);
			return;
		}

		float yawBack = yawOf(dir) + 180f;
		turnTo(p, yawBack, 80f);
		boolean aimed = Math.abs(Mth.wrapDegrees(yawBack - p.getYRot())) < 3f && Math.abs(80f - p.getXRot()) < 3f;

		if (bridgeStage == 1) { // đã đặt, lùi lên ô mới
			if (!solidFloor(lv, bridgeTarget)) {
				if (++bridgeWait > 8) { bridgeStage = 0; bridgeWait = 0; }
				mc.options.keyDown.setDown(false);
				return;
			}
			double hd = Math.hypot(p.getX() - (bridgeTarget.getX() + 0.5), p.getZ() - (bridgeTarget.getZ() + 0.5));
			if (cur.equals(bridgeTarget) && hd < 0.3) {
				mc.options.keyDown.setDown(false);
				sBridge++;
				pathIdx++;
				if (pathIdx < path.size()) { // ô kế cũng cần đặt cùng hướng thì đi tiếp ở chế độ này
					BlockPos nx = path.get(pathIdx);
					if (dirOf(bridgeTarget, nx) == dir && nx.getY() == bridgeTarget.getY()
							&& bridgeSet.contains(nx.asLong()) && !solidFloor(lv, nx)) {
						bridgeFrom = bridgeTarget;
						bridgeTarget = nx;
						bridgeStage = 0;
						bridgeTimer = 0;
						bridgeTries = 0;
						bridgeWait = 0;
						cooldown = 2;
						return;
					}
				}
				releaseKeys(mc);
				stepTimer = 0;
				stuckTicks = 0;
				setPhase(PH_WALK);
				return;
			}
			mc.options.keyDown.setDown(aimed);
			return;
		}

		// giai đoạn 0: ra mép và đặt block
		Vec3 eye = p.getEyePosition();
		BlockHitResult hit = lv.clip(new ClipContext(eye, eye.add(p.getViewVector(1f).scale(4.5)),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		boolean good = aimed && hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(bridgeFrom.below()) && hit.getDirection() == dir;
		if (good) {
			mc.options.keyDown.setDown(false);
			if (++bridgeTries > 4) { bridgeAbort(mc, true); return; }
			mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			p.swing(InteractionHand.MAIN_HAND);
			bridgeStage = 1;
			bridgeWait = 0;
			cooldown = 3 + p.getRandom().nextInt(3);
			return;
		}
		mc.options.keyDown.setDown(aimed); // lùi ra mép (chế độ cúi chặn không cho rơi)
	}

	private void bridgeAbort(Minecraft mc, boolean retreat) {
		bridgeBanUntil = tickNow + 600; // tạm không bắc cầu để tránh lặp lỗi
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		if (retreat) {
			bridgeStage = 2;
			bridgeTimer = 0;
		} else {
			releaseKeys(mc);
			replans = 0;
			setPhase(PH_PLAN);
			cooldown = 6;
		}
	}

	// Đưa block bắc cầu từ túi lên hotbar
	private void doBlockSwap(Minecraft mc, LocalPlayer p) {
		if (findBridgeHotbar(p) >= 0) {
			if (mc.screen instanceof InventoryScreen) p.closeContainer();
			setPhase(PH_WALK);
			cooldown = 8 + p.getRandom().nextInt(8);
			return;
		}
		if (++timer > 200) { p.closeContainer(); stop(p, "Chuyen block bac cau len hotbar qua lau."); return; }
		if (!(mc.screen instanceof InventoryScreen)) {
			mc.setScreen(new InventoryScreen(p));
			cooldown = 8 + p.getRandom().nextInt(8);
			return;
		}
		AbstractContainerMenu m = p.inventoryMenu;
		int src = -1;
		for (int s = 9; s <= 35 && src < 0; s++) if (isBridgeItem(m.getSlot(s).getItem())) src = s;
		if (src < 0) { p.closeContainer(); stop(p, "Het block de bac cau."); return; }
		int target = 7;
		for (int h = 0; h <= 7; h++) {
			if (!m.getSlot(36 + h).hasItem()) { target = h; break; }
		}
		click(mc, p, m.containerId, src, target, ClickType.SWAP);
		cooldown = menuDelay(p);
	}

	private static boolean isBridgeItem(ItemStack s) {
		for (Item i : BRIDGE_ITEMS) if (s.is(i)) return true;
		return false;
	}

	private static int countBridge(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (isBridgeItem(s)) n += s.getCount();
		}
		return n;
	}

	private static int findBridgeHotbar(LocalPlayer p) {
		for (int i = 0; i < 9; i++) if (isBridgeItem(p.getInventory().getItem(i))) return i;
		return -1;
	}

	private static Direction dirOf(BlockPos a, BlockPos b) {
		int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
		if (dx == 1 && dz == 0) return Direction.EAST;
		if (dx == -1 && dz == 0) return Direction.WEST;
		if (dz == 1 && dx == 0) return Direction.SOUTH;
		if (dz == -1 && dx == 0) return Direction.NORTH;
		return null;
	}

	private static float yawOf(Direction d) {
		return (float) Math.toDegrees(Math.atan2(d.getStepZ(), d.getStepX())) - 90f;
	}

	// ===================== MÚC LAVA =====================

	private static boolean isTopSource(ClientLevel lv, BlockPos pos) {
		FluidState f = lv.getFluidState(pos);
		if (!f.is(FluidTags.LAVA) || !f.isSource()) return false;
		BlockPos up = pos.above();
		FluidState uf = lv.getFluidState(up);
		if (uf.is(FluidTags.LAVA) && uf.isSource()) return false;
		return lv.getBlockState(up).getCollisionShape(lv, up).isEmpty();
	}

	private boolean rayAt(Minecraft mc, LocalPlayer p, BlockPos target, boolean useView) {
		Vec3 eye = p.getEyePosition();
		Vec3 dir = useView ? p.getViewVector(1f) : Vec3.atCenterOf(target).subtract(eye).normalize();
		BlockHitResult r = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(5.0)),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
		return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(target);
	}

	// Chọn nguồn lava trong tầm với: ưu tiên lava ngang tầng với sàn đang đứng, rồi tới gần nhất
	private BlockPos findScoopable(Minecraft mc, LocalPlayer p) {
		ClientLevel lv = mc.level;
		Vec3 eye = p.getEyePosition();
		BlockPos b = p.blockPosition();
		int floorY = b.getY() - 1;
		List<BlockPos> cand = new ArrayList<>();
		for (int dx = -5; dx <= 5; dx++)
			for (int dz = -5; dz <= 5; dz++)
				for (int dy = -7; dy <= 3; dy++) {
					BlockPos pos = b.offset(dx, dy, dz);
					if (!lv.getFluidState(pos).is(FluidTags.LAVA)) continue;
					if (blacklist.contains(pos.asLong()) || !isTopSource(lv, pos)) continue;
					double d = eye.distanceTo(Vec3.atCenterOf(pos));
					if (d >= MIN_SCOOP && d <= REACH) cand.add(pos);
				}
		cand.sort(Comparator.comparingDouble(pos -> eye.distanceTo(Vec3.atCenterOf(pos)) - (pos.getY() == floorY ? 1.5 : 0)));
		for (int i = 0; i < Math.min(cand.size(), 12); i++) {
			if (rayAt(mc, p, cand.get(i), false)) return cand.get(i);
		}
		return null;
	}

	private void doScoop(Minecraft mc, LocalPlayer p) {
		int lava = countItem(p, Items.LAVA_BUCKET), buckets = countItem(p, Items.BUCKET);
		if (lava >= optBatch || buckets == 0) { setPhase(PH_PLAN); return; }
		if (!p.getInventory().getItem(8).is(Items.BUCKET)) { setPhase(PH_PLAN); return; }
		if (p.getInventory().selected != 8) { p.getInventory().selected = 8; cooldown = 3; return; }

		if (scoopWait > 0) { // đã bấm, chờ kết quả
			if (lava > lavaBefore) {
				sScoop++;
				scoopWait = 0;
				scoopFail = 0;
				scoopTimer = 0;
				planRounds = 0;
				softCount = 0;
				badCells.clear();
				goalSrc = null;
				cooldown = 6 + p.getRandom().nextInt(5);
				setPhase(PH_PLAN);
			} else if (--scoopWait == 0) {
				if (++scoopFail >= 3 && goalSrc != null) {
					blacklist.add(goalSrc.asLong());
					scoopFail = 0;
					goalSrc = null;
					setPhase(PH_PLAN);
				}
			}
			return;
		}
		if (goalSrc == null || !isTopSource(mc.level, goalSrc)) { setPhase(PH_PLAN); return; }

		Vec3 eye = p.getEyePosition(), c = Vec3.atCenterOf(goalSrc);
		if (eye.distanceTo(c) > REACH + 0.2) { setPhase(PH_PLAN); return; }
		double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		turnTo(p, yaw, pitch);
		boolean aimed = Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) < 2f && Math.abs(pitch - p.getXRot()) < 2f;
		if (!aimed || !rayAt(mc, p, goalSrc, true)) {
			if (++scoopTimer > 100) {
				blacklist.add(goalSrc.asLong());
				goalSrc = null;
				scoopTimer = 0;
				setPhase(PH_PLAN);
			}
			return;
		}
		lavaBefore = lava;
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		scoopWait = 12;
	}

	// ===================== ĂN (tay trái) =====================

	private static boolean isFood(ItemStack s) {
		return !s.isEmpty() && s.has(DataComponents.FOOD);
	}

	private static boolean isAutoFood(ItemStack s) {
		if (!isFood(s)) return false;
		for (Item i : BAD_FOOD) if (s.is(i)) return false;
		return true;
	}

	private static int findFoodSlot(LocalPlayer p) {
		for (int i = 0; i < 36; i++) if (isAutoFood(p.getInventory().getItem(i))) return i;
		return -1;
	}

	private static boolean foodAvailable(LocalPlayer p) {
		ItemStack off = p.getOffhandItem();
		return isFood(off) || (off.isEmpty() && findFoodSlot(p) >= 0);
	}

	private void doEat(Minecraft mc, LocalPlayer p) {
		ItemStack off = p.getOffhandItem();
		if (!isFood(off)) {
			if (off.isEmpty() && findFoodSlot(p) >= 0) { setPhase(PH_FOODSWAP); return; }
			eatBanUntil = tickNow + (off.isEmpty() ? 1200 : 6000); // không có đồ ăn dùng được: thử lại sau
			setPhase(PH_PLAN);
			return;
		}
		if (eatStage == 0) {
			mc.gameMode.useItem(p, InteractionHand.OFF_HAND);
			eatStage = 1;
			eatTimer = 0;
			return;
		}
		mc.options.keyUse.setDown(true); // giữ chuột phải đến khi ăn xong
		eatTimer++;
		if ((!p.isUsingItem() && eatTimer > 4) || eatTimer > 100) {
			mc.options.keyUse.setDown(false);
			if (eatTimer > 100) eatBanUntil = tickNow + 600;
			eatStage = 0;
			setPhase(PH_PLAN);
			cooldown = 10;
		}
	}

	// Đưa đồ ăn từ túi lên tay trái (chỉ khi tay trái đang trống)
	private void doFoodSwap(Minecraft mc, LocalPlayer p) {
		if (isFood(p.getOffhandItem())) {
			if (mc.screen instanceof InventoryScreen) p.closeContainer();
			eatStage = 0;
			setPhase(PH_EAT);
			cooldown = 6;
			return;
		}
		if (++timer > 160 || !p.getOffhandItem().isEmpty()) {
			if (mc.screen instanceof InventoryScreen) p.closeContainer();
			eatBanUntil = tickNow + 1200;
			setPhase(PH_PLAN);
			return;
		}
		if (!(mc.screen instanceof InventoryScreen)) {
			mc.setScreen(new InventoryScreen(p));
			cooldown = 8 + p.getRandom().nextInt(8);
			return;
		}
		int src = findFoodSlot(p);
		if (src < 0) {
			p.closeContainer();
			eatBanUntil = tickNow + 1200;
			setPhase(PH_PLAN);
			return;
		}
		int menuSlot = src < 9 ? 36 + src : src;
		click(mc, p, p.inventoryMenu.containerId, menuSlot, 40, ClickType.SWAP); // 40 = tay trái
		cooldown = menuDelay(p);
	}

	// ===================== BÁN: /ah sell =====================

	private void doSell(Minecraft mc, LocalPlayer p) {
		if (mc.screen != null || p.containerMenu != p.inventoryMenu) return;
		int lavaTotal = countItem(p, Items.LAVA_BUCKET);
		if (lavaTotal == 0) {
			cooldown = 15 + p.getRandom().nextInt(15);
			if (countItem(p, Items.BUCKET) == 0) {
				if (!optOrder) { stop(p, "Het xo rong (dang tat lay xo tu /order)."); return; }
				orderAttempts = 0;
				setPhase(PH_R_CMD);
			} else {
				setPhase(PH_PLAN);
			}
			return;
		}

		boolean anyHot = false;
		int slot = -1;
		for (int i = 0; i < 8; i++) {
			if (!p.getInventory().getItem(i).is(Items.LAVA_BUCKET)) { sellSent[i] = false; sellTries[i] = 0; continue; }
			anyHot = true;
			if (tickNow < pendingUntil[i]) continue;
			if (sellSent[i]) { sellTries[i]++; sellSent[i] = false; } // lần trước gõ lệnh mà xô vẫn còn
			if (sellTries[i] >= 3) continue;                          // bán không được 3 lần: bỏ qua ô này
			if (slot < 0) slot = i;
		}
		if (!anyHot) { setPhase(PH_SPREAD); return; }
		if (slot < 0) {
			boolean allSkipped = true;
			for (int i = 0; i < 8; i++) {
				if (p.getInventory().getItem(i).is(Items.LAVA_BUCKET) && (tickNow < pendingUntil[i] || sellTries[i] < 3)) allSkipped = false;
			}
			if (allSkipped) { // bán không được thì kệ, đi tiếp (lấy xô hoặc múc tiếp)
				Arrays.fill(sellTries, 0);
				cooldown = 40;
				if (countItem(p, Items.BUCKET) == 0) {
					if (!optOrder) { stop(p, "Khong ban duoc xo lava va het xo rong."); return; }
					orderAttempts = 0;
					setPhase(PH_R_CMD);
				} else {
					setPhase(PH_PLAN);
				}
			}
			return;
		}

		boolean probe = false;
		if (blocked) {
			if (PROBE_MINUTES > 0 && tickNow - blockedSince > PROBE_MINUTES * 1200L) probe = true;
			else return;
		}
		if (p.getInventory().selected != slot) {
			p.getInventory().selected = slot;
			cooldown = 2;
			return;
		}
		if (probe) blockedSince = tickNow;
		mc.getConnection().sendCommand("ah sell " + priceText);
		pendingUntil[slot] = tickNow + PENDING_TICKS;
		sellSent[slot] = true;
		cooldown = SELL_MIN + p.getRandom().nextInt(SELL_VAR); // cộng thêm 2 tick đổi ô: 0,5 - 1,0 giây mỗi xô
	}

	// Chuyển xô lava từ túi xuống ô hotbar 1-8 còn trống
	private void doSpread(Minecraft mc, LocalPlayer p) {
		if (++timer > 200) { p.closeContainer(); stop(p, "Chuyen xo lava xuong hotbar qua lau."); return; }
		if (!(mc.screen instanceof InventoryScreen)) {
			mc.setScreen(new InventoryScreen(p));
			cooldown = 8 + p.getRandom().nextInt(8);
			return;
		}
		AbstractContainerMenu m = p.inventoryMenu;
		int free = -1, src = -1;
		for (int h = 0; h <= 7 && free < 0; h++) if (!m.getSlot(36 + h).hasItem()) free = h;
		for (int s = 9; s <= 35 && src < 0; s++) if (m.getSlot(s).getItem().is(Items.LAVA_BUCKET)) src = s;
		if (free < 0 || src < 0) {
			p.closeContainer();
			setPhase(PH_SELL);
			cooldown = 10 + p.getRandom().nextInt(10);
			return;
		}
		click(mc, p, m.containerId, src, free, ClickType.SWAP);
		cooldown = menuDelay(p);
	}

	// Sắp xếp xô rỗng: không để trong ô 1-8, đưa 1 stack vào ô 9
	private void doBucket(Minecraft mc, LocalPlayer p) {
		if (++timer > 200) { p.closeContainer(); stop(p, "Sap xep xo rong qua lau (tui day?)."); return; }
		if (!(mc.screen instanceof InventoryScreen)) {
			mc.setScreen(new InventoryScreen(p));
			cooldown = 8 + p.getRandom().nextInt(8);
			return;
		}
		AbstractContainerMenu m = p.inventoryMenu;
		for (int h = 0; h <= 7; h++) {
			if (m.getSlot(36 + h).getItem().is(Items.BUCKET)) {
				click(mc, p, m.containerId, 36 + h, 0, ClickType.QUICK_MOVE);
				cooldown = menuDelay(p);
				return;
			}
		}
		if (!m.getSlot(44).getItem().is(Items.BUCKET)) {
			int best = -1, cnt = 0;
			for (int s = 9; s <= 35; s++) {
				ItemStack st = m.getSlot(s).getItem();
				if (st.is(Items.BUCKET) && st.getCount() > cnt) { best = s; cnt = st.getCount(); }
			}
			if (best >= 0) {
				click(mc, p, m.containerId, best, 8, ClickType.SWAP);
				cooldown = menuDelay(p);
				return;
			}
		}
		p.closeContainer();
		setPhase(PH_PLAN);
		cooldown = 10 + p.getRandom().nextInt(10);
	}

	// ===================== LẤY XÔ: /order =====================

	private void orderFail(LocalPlayer p, String reason) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen instanceof AbstractContainerScreen<?>) p.closeContainer();
		if (++orderAttempts >= 3) {
			stop(p, "Khong lay duoc xo tu /order: " + reason + ". Mod dung yen.");
			return;
		}
		setPhase(PH_R_CMD);
		cooldown = 400 + p.getRandom().nextInt(200); // chờ 20-30 giây rồi gõ lại /order
	}

	private void doOrderPage(Minecraft mc, LocalPlayer p) {
		String t = menuTitle(mc);
		if (t.contains("your orders")) { setPhase(PH_R_LIST); return; }
		if (!(p.containerMenu instanceof ChestMenu cm) || !t.contains("orders (page")) {
			if (++timer > 160) orderFail(p, "menu /order khong mo");
			return;
		}
		if (menuAge++ < 8) return;
		int i = findNamed(cm, cm.slots.size() - 36, "your orders", false);
		if (i >= 0) {
			click(mc, p, cm.containerId, i, 0, ClickType.PICKUP);
			setPhase(PH_R_LIST);
			cooldown = menuDelay(p) + 4;
			return;
		}
		if (++timer > 160) orderFail(p, "khong thay nut Your Orders");
	}

	private void doOrderList(Minecraft mc, LocalPlayer p) {
		if (!(p.containerMenu instanceof ChestMenu cm) || !menuTitle(mc).contains("your orders")) {
			if (++timer > 160) orderFail(p, "menu Your Orders khong mo");
			return;
		}
		if (menuAge++ < 8) return;
		int limit = cm.slots.size() - 36 - 9; // bỏ hàng nút cuối
		for (int i = 0; i < limit; i++) {
			if (cm.getSlot(i).getItem().is(Items.BUCKET)) {
				click(mc, p, cm.containerId, i, 0, ClickType.PICKUP);
				setPhase(PH_R_EDIT);
				cooldown = menuDelay(p) + 4;
				return;
			}
		}
		p.closeContainer();
		stop(p, "Khong co don xo (bucket) trong Your Orders. Mod dung yen.");
	}

	private void doOrderEdit(Minecraft mc, LocalPlayer p) {
		if (!(p.containerMenu instanceof ChestMenu cm) || !menuTitle(mc).contains("edit order")) {
			if (++timer > 160) orderFail(p, "menu Edit Order khong mo");
			return;
		}
		if (menuAge++ < 8) return;
		int i = findNamed(cm, cm.slots.size() - 36, "collect", true); // chỉ bấm rương "Collect"
		if (i >= 0) {
			click(mc, p, cm.containerId, i, 0, ClickType.PICKUP);
			setPhase(PH_R_COLLECT);
			cooldown = menuDelay(p) + 4;
			return;
		}
		if (++timer > 160) orderFail(p, "khong thay nut Collect");
	}

	private void doOrderCollect(Minecraft mc, LocalPlayer p) {
		if (!(p.containerMenu instanceof ChestMenu cm) || !menuTitle(mc).contains("collect items")) {
			if (++timer > 160) orderFail(p, "menu Collect Items khong mo");
			return;
		}
		if (menuAge++ < 8) return;
		int limit = cm.slots.size() - 36 - 9; // chỉ lấy ở các hàng trên, không đụng hàng nút
		if (orderTaken < optStacks && freeSlots(cm) > 2) {
			int now = countItem(p, Items.BUCKET);
			if (now == lastCount) {
				if (++noProgress > 4) { orderFail(p, "khong nhat duoc xo tu Collect Items"); return; }
			} else {
				noProgress = 0;
			}
			lastCount = now;
			for (int i = 0; i < limit; i++) {
				if (cm.getSlot(i).getItem().is(Items.BUCKET)) {
					orderTaken++;
					click(mc, p, cm.containerId, i, 0, ClickType.QUICK_MOVE);
					cooldown = menuDelay(p);
					return;
				}
			}
		}
		p.closeContainer();
		int got = countItem(p, Items.BUCKET) - bucketsBefore;
		if (got <= 0) { stop(p, "Het xo: don /order khong con xo de lay. Mod dung yen."); return; }
		orderAttempts = 0;
		if (orderTest) { finishInfo(p, "Thu /order xong: lay duoc " + got + " xo."); return; }
		setPhase(PH_BUCKET);
		cooldown = 20 + p.getRandom().nextInt(20);
	}

	private static int findNamed(ChestMenu cm, int limit, String key, boolean chestOnly) {
		for (int i = 0; i < limit; i++) {
			ItemStack s = cm.getSlot(i).getItem();
			if (s.isEmpty() || (chestOnly && !s.is(Items.CHEST))) continue;
			if (s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(key)) return i;
		}
		return -1;
	}

	// ===================== TIN NHẮN VÀ THỐNG KÊ =====================

	private void onMessage(Component msg) {
		String s = msg.getString().toLowerCase(Locale.ROOT);
		boolean red = s.contains("too many listed items");
		boolean total = s.contains("you earned") && s.contains("from auction");
		boolean buy = s.contains("bought your");
		boolean list = s.contains("you listed");
		if (!(red || total || buy || list)) return;
		rollover();
		if (buy) addSale(1, parseMoney(s));
		if (total) {
			long v = parseMoney(s);
			addSale(priceValue > 0 ? (int) Math.max(1, Math.round((double) v / priceValue)) : 1, v);
		}
		if (!running) return;
		if (red) {
			blocked = true;
			blockedSince = tickNow;
		} else if (total || buy || list) {
			blocked = false;
		}
	}

	private void addSale(int n, long money) {
		dSold += n;
		dMoney += money;
		if (running) { sSold += n; sMoney += money; }
		dirty = true;
	}

	private static long parseMoney(String lower) {
		Matcher m = MONEY.matcher(lower);
		if (!m.find()) return 0;
		try {
			double v = Double.parseDouble(m.group(1).replace(",", ""));
			String sf = m.group(2);
			if (sf.equals("k")) v *= 1e3;
			else if (sf.equals("m")) v *= 1e6;
			else if (sf.equals("b")) v *= 1e9;
			return Math.round(v);
		} catch (Exception e) {
			return 0;
		}
	}

	// ===================== HIỂN THỊ (bảng thống kê + chữ cảnh báo) =====================

	private String statusText() {
		if (!running) return "tat (K bat, P menu)";
		return switch (phase) {
			case PH_SELL, PH_SPREAD -> blocked ? "day, cho nguoi mua" : "dang ban";
			case PH_BRIDGE, PH_BLOCKSWAP -> "dang dat block bac cau";
			case PH_WALK -> "dang di toi cho co lava";
			case PH_SCOOP -> "dang muc lava";
			case PH_BUCKET -> "dang sap xep xo";
			case PH_EAT, PH_FOODSWAP -> "dang an";
			case PH_WAIT -> "cho thu lai: " + waitReason;
			case PH_R_CMD, PH_R_PAGE, PH_R_LIST, PH_R_EDIT, PH_R_COLLECT -> "dang lay xo tu /order";
			default -> "dang tim lava";
		};
	}

	private void drawPanel(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (!showPanel || mc.player == null || mc.getConnection() == null || mc.options.hideGui) return;
		List<String> lines = new ArrayList<>();
		lines.add("LavaSeller [" + (running ? "ON" : "OFF") + "]  gia " + (priceText.isEmpty() ? "-" : priceText));
		lines.add("Phien nay: muc " + sScoop + " | ban " + sSold + " | cau " + sBridge + " | $" + fmtShort(sMoney));
		lines.add("Hom nay: ban " + dSold + " xo | $" + fmtShort(dMoney));
		lines.add(statusText());
		int w = 0;
		for (String l : lines) w = Math.max(w, mc.font.width(l));
		int x2 = g.guiWidth() - 4, x1 = x2 - w - 8, y1 = 4, y2 = y1 + lines.size() * 10 + 6;
		g.fill(x1, y1, x2, y2, 0x90000000);
		int y = y1 + 4;
		for (String l : lines) {
			g.drawString(mc.font, l, x1 + 4, y, 0xFFFFFF, true);
			y += 10;
		}
	}

	private boolean alertActive() {
		return alertKind != A_NONE && tickNow - alertTick <= alertDur;
	}

	private void alert(int kind, String text) {
		alertKind = kind;
		alertText = text;
		alertTick = tickNow;
		alertDur = kind == A_ERROR ? ALERT_TICKS : kind == A_CHECK ? CHECK_TICKS : 300;
	}

	// Chữ to, mờ, nằm cao hơn tâm màn hình một chút để không cản trở việc chơi
	private void drawAlert(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		if (alertKind == A_NONE || mc.player == null || mc.options.hideGui) return;
		long age = tickNow - alertTick;
		if (age > alertDur) { alertKind = A_NONE; return; }
		long left = alertDur - age;
		int alpha = 150;
		if (left < 200) alpha = (int) (150 * left / 200.0); // mờ dần ở 10 giây cuối
		if (alpha < 8) return;
		int rgb = alertKind == A_ERROR ? 0xFF4040 : alertKind == A_CHECK ? 0xFFD040 : 0x50FF50;
		int color = (alpha << 24) | rgb;
		float scale = 2.0f;
		int maxW = (int) (g.guiWidth() * 0.8f / scale);
		List<FormattedCharSequence> lines = new ArrayList<>();
		for (String part : alertText.split("\n")) lines.addAll(mc.font.split(Component.literal(part), maxW));
		g.pose().pushPose();
		g.pose().scale(scale, scale, 1f);
		int y = (int) (g.guiHeight() * 0.28f / scale) - lines.size() * 5;
		for (FormattedCharSequence l : lines) {
			int w = mc.font.width(l);
			g.drawString(mc.font, l, (int) (g.guiWidth() / scale / 2) - w / 2, y, color, true);
			y += 10;
		}
		g.pose().popPose();
	}

	// ===================== LƯU FILE =====================

	private static Path statsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("lavaseller-stats.txt");
	}

	private static Path settingsFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("lavaseller-settings.properties");
	}

	private void rollover() {
		String today = LocalDate.now().toString();
		if (!today.equals(statDate)) { statDate = today; dSold = 0; dMoney = 0; dirty = true; }
	}

	private void saveStats() {
		try {
			Files.write(statsFile(), List.of(statDate, dSold + " " + dMoney));
			dirty = false;
		} catch (Exception ignored) { }
	}

	private void loadStats() {
		statDate = LocalDate.now().toString();
		try {
			if (!Files.exists(statsFile())) return;
			List<String> lines = Files.readAllLines(statsFile());
			if (lines.size() < 2 || !lines.get(0).trim().equals(statDate)) return; // khác ngày -> bắt đầu lại
			String[] t = lines.get(1).trim().split(" ");
			dSold = Integer.parseInt(t[0]);
			dMoney = Long.parseLong(t[1]);
		} catch (Exception ignored) { }
	}

	private static void saveSettings() {
		try (OutputStream os = Files.newOutputStream(settingsFile())) {
			Properties pr = new Properties();
			pr.setProperty("bridge", String.valueOf(optBridge));
			pr.setProperty("order", String.valueOf(optOrder));
			pr.setProperty("background", String.valueOf(optBackground));
			pr.setProperty("eat", String.valueOf(optEat));
			pr.setProperty("avoidsoul", String.valueOf(optAvoidSoul));
			pr.setProperty("escape", String.valueOf(optEscape));
			pr.setProperty("batch", String.valueOf(optBatch));
			pr.setProperty("stacks", String.valueOf(optStacks));
			pr.setProperty("radius", String.valueOf(optRadius));
			pr.store(os, "LavaSeller");
		} catch (Exception ignored) { }
	}

	private static void loadSettings() {
		try {
			if (!Files.exists(settingsFile())) return;
			Properties pr = new Properties();
			try (InputStream is = Files.newInputStream(settingsFile())) { pr.load(is); }
			optBridge = Boolean.parseBoolean(pr.getProperty("bridge", "true"));
			optOrder = Boolean.parseBoolean(pr.getProperty("order", "true"));
			optBackground = Boolean.parseBoolean(pr.getProperty("background", "true"));
			optEat = Boolean.parseBoolean(pr.getProperty("eat", "true"));
			optAvoidSoul = Boolean.parseBoolean(pr.getProperty("avoidsoul", "true"));
			optEscape = Mth.clamp(Integer.parseInt(pr.getProperty("escape", "50")), 10, 200);
			optBatch = Mth.clamp(Integer.parseInt(pr.getProperty("batch", "16")), 1, 30);
			optStacks = Mth.clamp(Integer.parseInt(pr.getProperty("stacks", "5")), 1, 9);
			optRadius = Mth.clamp(Integer.parseInt(pr.getProperty("radius", "100")), 20, 150);
		} catch (Exception ignored) { }
	}

	// ===================== TIỆN ÍCH =====================

	private void setPhase(int ph) {
		phase = ph;
		timer = 0;
		menuAge = 0;
		noProgress = 0;
		lastCount = -1;
		scoopWait = 0;
		if (ph == PH_PLAN) planStage = 0;
	}

	private int menuDelay(LocalPlayer p) {
		return 5 + p.getRandom().nextInt(6);
	}

	private void click(Minecraft mc, LocalPlayer p, int containerId, int slot, int button, ClickType type) {
		mc.gameMode.handleInventoryMouseClick(containerId, slot, button, type, p);
	}

	private static void turnTo(LocalPlayer p, float yaw, float pitch) {
		float dy = Mth.clamp(Mth.wrapDegrees(yaw - p.getYRot()), -TURN_SPEED, TURN_SPEED);
		float dp = Mth.clamp(pitch - p.getXRot(), -TURN_SPEED, TURN_SPEED);
		p.setYRot(p.getYRot() + dy);
		p.setXRot(Mth.clamp(p.getXRot() + dp, -90f, 90f));
	}

	private static String menuTitle(Minecraft mc) {
		if (mc.screen instanceof AbstractContainerScreen<?> s) return s.getTitle().getString().toLowerCase(Locale.ROOT);
		return "";
	}

	private static int countItem(LocalPlayer p, Item item) {
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = p.getInventory().getItem(i);
			if (s.is(item)) n += s.getCount();
		}
		return n;
	}

	private static int emptySlots(LocalPlayer p) {
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	private static int freeSlots(AbstractContainerMenu m) {
		int n = 0;
		for (int i = m.slots.size() - 36; i < m.slots.size(); i++) if (!m.getSlot(i).hasItem()) n++;
		return n;
	}

	private static void releaseKeys(Minecraft mc) {
		mc.options.keyUp.setDown(false);
		mc.options.keyDown.setDown(false);
		mc.options.keyLeft.setDown(false);
		mc.options.keyRight.setDown(false);
		mc.options.keyJump.setDown(false);
		mc.options.keyShift.setDown(false);
		mc.options.keyUse.setDown(false);
	}

	private static String fmtShort(long v) {
		if (v >= 1_000_000_000L) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
		if (v >= 1_000_000L) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
		if (v >= 1_000L) return String.format(Locale.ROOT, "%.1fK", v / 1e3);
		return String.valueOf(v);
	}

	// Hỗ trợ: 29999, 20k, 1.5m, 2b (dùng dấu chấm cho số thập phân)
	static long parsePrice(String raw) {
		String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").replace(" ", "");
		if (s.isEmpty()) return -1;
		double mult = 1;
		char last = s.charAt(s.length() - 1);
		if (last == 'k') mult = 1e3;
		else if (last == 'm') mult = 1e6;
		else if (last == 'b') mult = 1e9;
		if (mult != 1) s = s.substring(0, s.length() - 1);
		try {
			double v = Double.parseDouble(s) * mult;
			if (v < 1 || v > 1e12) return -1;
			return Math.round(v);
		} catch (Exception e) {
			return -1;
		}
	}

	private boolean applyPrice(String raw) {
		long v = parsePrice(raw);
		if (v <= 0) return false;
		priceText = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
		priceValue = v;
		return true;
	}

	// ===================== MENU CÀI ĐẶT (phím P) =====================

	private static class MenuScreen extends Screen {
		private final LavasellerClient owner;
		private EditBox priceBox;
		private Component err = Component.empty();
		private Button bBridge, bOrder, bBg, bEat, bSoul;
		private int top, cx, lx, rx, rowY;

		MenuScreen(LavasellerClient owner) {
			super(Component.literal("LavaSeller - Cai dat"));
			this.owner = owner;
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}

		private static String onOff(boolean b) {
			return b ? "BAT" : "TAT";
		}

		private void refresh() {
			bBridge.setMessage(Component.literal("Bac cau dat block: " + onOff(optBridge)));
			bOrder.setMessage(Component.literal("Lay xo tu /order: " + onOff(optOrder)));
			bBg.setMessage(Component.literal("Chay nen (an cua so): " + onOff(optBackground)));
			bEat.setMessage(Component.literal("Tu an (tay trai): " + onOff(optEat)));
			bSoul.setMessage(Component.literal("Tranh cat linh hon: " + onOff(optAvoidSoul)));
		}

		private void numRow(int index, int step, int min, int max, int which) {
			int y = rowY + index * 24;
			addRenderableWidget(Button.builder(Component.literal("-"), b -> { change(which, -step, min, max); })
					.bounds(rx + 140, y, 18, 20).build());
			addRenderableWidget(Button.builder(Component.literal("+"), b -> { change(which, step, min, max); })
					.bounds(rx + 184, y, 18, 20).build());
		}

		private void change(int which, int delta, int min, int max) {
			switch (which) {
				case 0 -> optEscape = Mth.clamp(optEscape + delta, min, max);
				case 1 -> optBatch = Mth.clamp(optBatch + delta, min, max);
				case 2 -> optStacks = Mth.clamp(optStacks + delta, min, max);
				default -> optRadius = Mth.clamp(optRadius + delta, min, max);
			}
			saveSettings();
		}

		@Override
		protected void init() {
			cx = this.width / 2;
			top = Math.max(6, this.height / 2 - 130);
			lx = cx - 205;
			rx = cx + 5;
			rowY = top + 62;

			priceBox = new EditBox(this.font, cx - 100, top + 30, 200, 20, Component.literal("Gia"));
			priceBox.setMaxLength(20);
			priceBox.setValue(owner.priceText);
			this.addRenderableWidget(priceBox);
			this.setInitialFocus(priceBox);

			bBridge = addRenderableWidget(Button.builder(Component.empty(), b -> { optBridge = !optBridge; refresh(); saveSettings(); })
					.bounds(lx, rowY, 200, 20).build());
			bOrder = addRenderableWidget(Button.builder(Component.empty(), b -> { optOrder = !optOrder; refresh(); saveSettings(); })
					.bounds(lx, rowY + 24, 200, 20).build());
			bBg = addRenderableWidget(Button.builder(Component.empty(), b -> { optBackground = !optBackground; refresh(); saveSettings(); })
					.bounds(lx, rowY + 48, 200, 20).build());
			bEat = addRenderableWidget(Button.builder(Component.empty(), b -> { optEat = !optEat; refresh(); saveSettings(); })
					.bounds(lx, rowY + 72, 200, 20).build());
			bSoul = addRenderableWidget(Button.builder(Component.empty(), b -> { optAvoidSoul = !optAvoidSoul; refresh(); saveSettings(); })
					.bounds(lx, rowY + 96, 200, 20).build());
			refresh();

			numRow(0, 5, 10, 200, 0);
			numRow(1, 1, 1, 30, 1);
			numRow(2, 1, 1, 9, 2);
			numRow(3, 10, 20, 150, 3);

			int by = rowY + 126;
			addRenderableWidget(Button.builder(Component.literal("BAT DAU / DUNG"), b -> {
				owner.applyPrice(priceBox.getValue());
				this.onClose();
				owner.toggleFromGui();
			}).bounds(cx - 155, by, 150, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Thu /order ngay"), b -> {
				this.onClose();
				owner.testOrderFromGui();
			}).bounds(cx + 5, by, 150, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Dong"), b -> this.onClose())
					.bounds(cx - 50, by + 24, 100, 20).build());
		}

		@Override
		public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
			if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
				if (owner.applyPrice(priceBox.getValue())) {
					err = Component.literal("Da luu gia " + owner.priceText);
				} else {
					err = Component.literal("Gia khong hop le. Vi du: 10k, 20k, 1.5m");
				}
				return true;
			}
			return super.keyPressed(keyCode, scanCode, modifiers);
		}

		@Override
		public void onClose() {
			owner.applyPrice(priceBox.getValue());
			super.onClose();
		}

		@Override
		public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
			super.render(g, mouseX, mouseY, delta);
			g.drawCenteredString(this.font, this.title, cx, top + 6, 0xFFFFFF);
			g.drawCenteredString(this.font, Component.literal("Gia ban (vi du 10k, 20k, 1.5m) - Enter de luu"), cx, top + 19, 0xAAAAAA);
			String[] labels = {"Thoat khi co nguoi", "So xo moi dot ban", "So stack xo /order", "Ban kinh di (block)"};
			int[] vals = {optEscape, optBatch, optStacks, optRadius};
			for (int i = 0; i < 4; i++) {
				int y = rowY + i * 24;
				g.drawString(this.font, labels[i], rx, y + 6, 0xFFFFFF, false);
				g.drawCenteredString(this.font, String.valueOf(vals[i]), rx + 171, y + 6, 0xFFFF55);
			}
			g.drawString(this.font, "Toc do ban: 0.5 - 1.0 giay moi xo (co dinh)", rx, rowY + 100, 0xAAAAAA, false);
			int sy = rowY + 126 + 50;
			if (!err.getString().isEmpty()) g.drawCenteredString(this.font, err, cx, sy, 0xFFAA00);
			String why = owner.lastStopReason.isEmpty() ? "(chua co)" : owner.lastStopReason;
			g.drawCenteredString(this.font, Component.literal("Ly do dung gan nhat: " + why), cx, sy + 12, 0xFF7777);
			g.drawCenteredString(this.font, Component.literal("Phien: muc " + owner.sScoop + " | ban " + owner.sSold
					+ " | $" + fmtShort(owner.sMoney) + "    Hom nay: ban " + owner.dSold + " | $" + fmtShort(owner.dMoney)),
					cx, sy + 24, 0xDDDDDD);
		}
	}
}
