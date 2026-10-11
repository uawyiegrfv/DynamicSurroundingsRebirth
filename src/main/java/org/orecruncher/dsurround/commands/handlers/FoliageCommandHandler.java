package org.orecruncher.dsurround.commands.handlers;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.orecruncher.dsurround.Configuration;
import org.orecruncher.dsurround.client.particle.FoliageDripParticle;
import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.Library;
import org.orecruncher.dsurround.processing.FoliageDewHandler;
import org.orecruncher.dsurround.processing.FoliageProfile;
import org.orecruncher.dsurround.processing.FoliageProfileCache;
import org.orecruncher.dsurround.processing.FoliageRainDripHandler;
import org.orecruncher.dsurround.processing.FoliageSnowHandler;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.orecruncher.dsurround.processing.scanner.FoliageScan;

/**
 * The answer to "is it the detection step or the render step".
 *
 * <p>A new layer that reads nothing in the log is undebuggable, so this walks
 * the pipeline in the order the data flows and prints what each stage holds:
 * the config gates, the world/weather state, the scan's rejection histogram,
 * the profile the model actually produced, and finally a forced spawn that
 * isolates rendering from everything before it.</p>
 *
 * <p>The histogram is the pivot. If {@code ok} is zero the plants never
 * reached the handlers and the largest of the other four counters says which
 * test turned them away; if {@code ok} is healthy but nothing is on screen,
 * the fault is downstream and {@code spawn} settles it in one step.</p>
 */
public final class FoliageCommandHandler {

    private FoliageCommandHandler() {
    }

    /**
     * Everything at once. Deliberately printed per-line rather than as one
     * blob: chat wrapping makes a long single message unreadable, and every
     * line also goes to the log where it survives.
     */
    public static Component overview() {
        final StringBuilder sb = new StringBuilder(512);
        final Player player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return Component.literal("[foliage] 不在游戏中");

        final Level world = player.level();
        final Configuration cfg = config();
        if (cfg == null)
            return Component.literal("[foliage] 拿不到配置（两个处理器都没实例化）");

        out("=== 植物滴水/晨露 诊断 ===");

        out(String.format("配置  rainDrips=%s dew=%s density=%.2f scanRange=%d",
                cfg.weatherOptions.enableFoliageRainDrips,
                cfg.weatherOptions.enableMorningDew,
                cfg.weatherOptions.foliageDensity,
                cfg.weatherOptions.foliageScanRange));

        final long dayTime = world.getDayTime();
        final float curve = FoliageDewHandler.dewCurve(dayTime);
        final float ambient = PrecipitationIntensity.ambient();
        out(String.format("世界  %s dayTime=%d(mod=%d) tick=%d raining=%s thunder=%s",
                world.dimension().location(), dayTime, Math.floorMod(dayTime, 24000L),
                world.getGameTime(), world.isRaining(), world.isThundering()));
        out(String.format("降水  ambient=%.3f  晨露曲线=%.3f (窗口 21600→3300, 峰值 23400)",
                ambient, curve));
        if (curve <= 0F)
            out("      ⚠ 当前不在晨露时间窗内 —— 用 /dsdebug foliage force 强制开启再测");
        if (ambient <= 0F)
            out("      ⚠ 当前没在下雨(ambient=0) —— 用 /dsdebug foliage force 强制开启再测");

        out("扫描  " + FoliageScan.statsLine());
        if (FoliageScan.LAST[FoliageScan.STAT_OK] == 0)
            out("      ⚠ ok=0：植物一格都没进池子。看上面四个拒绝计数里最大的那个：");
        out(String.format("      air=%d 空气  notCandidate=%d 不是植物  skyBlocked=%d 头顶不露天  noProfile=%d 模型没算出锚点",
                FoliageScan.LAST[FoliageScan.STAT_AIR],
                FoliageScan.LAST[FoliageScan.STAT_NOT_CANDIDATE],
                FoliageScan.LAST[FoliageScan.STAT_SKY],
                FoliageScan.LAST[FoliageScan.STAT_NO_PROFILE]));

        // 构建=0 ⇒ 这一层一次都没算过任何模型；超预算>0 是正常的（冷启动那一次
        // 几乎必然超 5ms），round 45 之后它只是计数，不再让某种植物失去积雪资格。
        out(String.format("模型  缓存=%d 构建=%d 超预算=%d 最近=%dus 最慢=%dus",
                FoliageProfileCache.size(), FoliageProfileCache.builds, FoliageProfileCache.overBudget,
                FoliageProfileCache.lastBuildNanos / 1000L, FoliageProfileCache.worstBuildNanos / 1000L));
        out(String.format("最近一次构建  quads=%d 去重后=%d 平面=%d 锚点=%d 路径=%d",
                FoliageProfile.lastQuads, FoliageProfile.lastDeduped,
                FoliageProfile.lastPlanes, FoliageProfile.lastAnchors, FoliageProfile.lastPaths));
        out(String.format("      丢弃: 退化=%d 非平行=%d 空掩码=%d",
                FoliageProfile.lastDegenerate, FoliageProfile.lastNonParallelogram,
                FoliageProfile.lastNoMask));

        final FoliageRainDripHandler rain = FoliageRainDripHandler.INSTANCE;
        out("雨露  " + (rain == null
                ? "⚠ 处理器未实例化（DI 接线没跑？）"
                : String.format("池=%d 存活=%d 已生成=%d (null=%d 类型错=%d) force=%s",
                        rain.poolSize(), rain.liveCount(), FoliageRainDripHandler.spawned,
                        FoliageRainDripHandler.spawnNull, FoliageRainDripHandler.spawnWrong,
                        FoliageRainDripHandler.DEBUG_FORCE)));

        final FoliageDewHandler dew = FoliageDewHandler.INSTANCE;
        out("晨露  " + (dew == null
                ? "⚠ 处理器未实例化（DI 接线没跑？）"
                : String.format("植物=%d 露珠=%d 已挂=%d (null=%d 类型错=%d) force=%s",
                        dew.plantCount(), dew.dropCount(), FoliageDewHandler.attached,
                        FoliageDewHandler.spawnNull, FoliageDewHandler.spawnWrong,
                        FoliageDewHandler.DEBUG_FORCE)));
        if (dew != null)
            out(String.format("      上次扫描拒绝: 雪温=%d 无露水trait=%d 概率=%d 无锚点=%d",
                    FoliageDewHandler.rejSnow, FoliageDewHandler.rejCoeff,
                    FoliageDewHandler.rejKeep, FoliageDewHandler.rejNoAttach));

        final FoliageSnowHandler snow = FoliageSnowHandler.INSTANCE;
        final var depthChannel = org.orecruncher.dsurround.processing.weather.PrecipitationResponse.FOLIAGE_SNOW;
        out("积雪  " + (snow == null
                ? "⚠ 处理器未实例化（DI 接线没跑？）"
                : String.format("深度=%.2f%s 植物=%d 单元=%d 已挂=%d (null=%d 类型错=%d) force=%s 每株上限=%s",
                        depthChannel.value(),
                        depthChannel.isLocked() ? "(锁定)" : "",
                        snow.plantCount(), snow.unitCount(), FoliageSnowHandler.attached,
                        FoliageSnowHandler.spawnNull, FoliageSnowHandler.spawnWrong,
                        FoliageSnowHandler.DEBUG_FORCE,
                        FoliageSnowHandler.DEBUG_UNITS > 0 ? FoliageSnowHandler.DEBUG_UNITS : "默认16")));
        out(String.format("      上次扫描拒绝: 雪温=%d 相位=%d 无锚点=%d",
                FoliageSnowHandler.rejWarm, FoliageSnowHandler.rejPhase, FoliageSnowHandler.rejNoAttach));

        out("下一步: /dsdebug foliage scan 看候选  |  profile 看锚点  |  spawn 只测渲染  |  force 强制开窗  |  snow 看雪层");
        sb.append("诊断完成（详见聊天与日志）");
        return Component.literal(sb.toString());
    }

    /** Forces one scan of each mode right now and prints what came back. */
    public static Component scan() {
        final Player player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return Component.literal("[foliage] 不在游戏中");
        final Level world = player.level();
        final BlockPos center = player.blockPosition();
        final Configuration cfg = config();
        if (cfg == null)
            return Component.literal("[foliage] 拿不到配置（两个处理器都没实例化）");
        final int range = cfg.weatherOptions.foliageScanRange;

        final boolean oldTrace = FoliageScan.TRACE;
        FoliageScan.TRACE = true;
        try {
            for (final FoliageScan.Mode mode : FoliageScan.Mode.values()) {
                final List<FoliageScan.FoliageSpot> found = collect(FoliageScan.scan(world, center, mode, range));
                out(String.format("[%s] %s", mode, FoliageScan.statsLine()));
                if (found.isEmpty()) {
                    out("    没有候选植物");
                    continue;
                }
                out(String.format("    前 %d 个候选:", Math.min(8, found.size())));
                for (int i = 0; i < Math.min(8, found.size()); i++) {
                    final FoliageScan.FoliageSpot spot = found.get(i);
                    int anchors = 0;
                    int paths = 0;
                    for (final FoliageProfile.Plane p : spot.profile().planes()) {
                        anchors += p.anchors().length;
                        paths += p.paths().length;
                    }
                    out(String.format("    #%d (%d,%d,%d) %s 平面=%d 锚点=%d 路径=%d",
                            i, spot.pos().getX(), spot.pos().getY(), spot.pos().getZ(),
                            spot.state().getBlock().getName().getString(),
                            spot.profile().planes().size(), anchors, paths));
                }
            }
        } finally {
            FoliageScan.TRACE = oldTrace;
        }
        return Component.literal("扫描完成（详见聊天与日志）");
    }

    /**
     * The profile of the nearest candidate plant: planes, their normals, and
     * where each anchor lands in WORLD space - which is exactly what "the
     * anchors are not on the visible leaf" has to be checked against.
     */
    public static Component profile() {
        final Player player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return Component.literal("[foliage] 不在游戏中");
        final Level world = player.level();
        final BlockPos center = player.blockPosition();

        final Configuration cfg = config();
        if (cfg == null)
            return Component.literal("[foliage] 拿不到配置（两个处理器都没实例化）");
        final List<FoliageScan.FoliageSpot> found = collect(
                FoliageScan.scan(world, center, FoliageScan.Mode.DEW, cfg.weatherOptions.foliageScanRange));
        if (found.isEmpty())
            return Component.literal("[foliage] 附近没有候选植物（先跑 scan 看拒绝原因）");

        // Nearest first - the one the player is most likely looking at.
        FoliageScan.FoliageSpot best = found.get(0);
        double bestD = Double.MAX_VALUE;
        for (final FoliageScan.FoliageSpot s : found) {
            final double d = s.pos().distToCenterSqr(player.getX(), player.getY(), player.getZ());
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }

        final BlockState state = best.state();
        out(String.format("最近植物 (%d,%d,%d) %s",
                best.pos().getX(), best.pos().getY(), best.pos().getZ(),
                state.getBlock().getName().getString()));
        out(String.format("  模型: %s", FoliageProfile.lastModel));
        out(String.format("  构建: quads=%d 去重后=%d 平面=%d 锚点=%d 路径=%d (退化=%d 非平行=%d 空掩码=%d)",
                FoliageProfile.lastQuads, FoliageProfile.lastDeduped, FoliageProfile.lastPlanes,
                FoliageProfile.lastAnchors, FoliageProfile.lastPaths,
                FoliageProfile.lastDegenerate, FoliageProfile.lastNonParallelogram,
                FoliageProfile.lastNoMask));
        if (FoliageProfile.lastPlanes > 2)
            out("  ⚠ 平面数 > 2：交叉模型应去重到 2 个平面，多了说明去重没生效（审查 B）");

        final net.minecraft.world.phys.Vec3 offset = state.getOffset(world, best.pos());
        out(String.format("  offset=(%.3f,%.3f,%.3f)", offset.x, offset.y, offset.z));
        int pi = 0;
        for (final FoliageProfile.Plane plane : best.profile().planes()) {
            out(String.format("  平面#%d 法线=(%.2f,%.2f,%.2f) 锚点=%d 路径=%d",
                    pi, plane.nx(), plane.ny(), plane.nz(),
                    plane.anchors().length, plane.paths().length));
            for (int i = 0; i < Math.min(3, plane.anchors().length); i++) {
                final FoliageProfile.Anchor a = plane.anchors()[i];
                out(String.format("    锚点%d 模型=(%.3f,%.3f,%.3f) → 世界=(%.3f,%.3f,%.3f) w=%.2f",
                        i, a.x(), a.y(), a.z(),
                        best.pos().getX() + offset.x + a.x(),
                        best.pos().getY() + offset.y + a.y(),
                        best.pos().getZ() + offset.z + a.z(),
                        a.weight()));
            }
            pi++;
        }
        return Component.literal("profile 完成（详见聊天与日志）");
    }

    /**
     * Spawns a ring of beads right in front of the player, with no scan and no
     * gate involved. If these are invisible the fault is in the particle or
     * its registration, not in detection.
     */
    public static Component spawn() {
        final Player player = GameUtils.getPlayer().orElse(null);
        if (player == null)
            return Component.literal("[foliage] 不在游戏中");
        final var pm = GameUtils.getParticleManager();
        if (pm == null)
            return Component.literal("[foliage] 拿不到 ParticleManager");

        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            return Component.literal("[foliage] 没有客户端世界");

        int made = 0;
        int bad = 0;
        for (int i = 0; i < 12; i++) {
            final double ang = i / 12.0D * Math.PI * 2.0D;
            final double x = player.getX() + Math.cos(ang) * 1.6D;
            final double y = player.getY() + 0.4D + (i % 3) * 0.25D;
            final double z = player.getZ() + Math.sin(ang) * 1.6D;
            final net.minecraft.client.particle.Particle p =
                    ((org.orecruncher.dsurround.mixins.core.MixinParticleManager) pm)
                            .dsurround_createParticle(
                                    org.orecruncher.dsurround.client.particle.ModParticles.FOLIAGE_DROP,
                                    x, y, z, 0, 0.6D, 0.0D);
            if (p == null) {
                bad++;
                continue;
            }
            pm.add(p);
            if (p instanceof FoliageDripParticle drop) {
                // A viewer-facing vertical plane: world up, and the horizontal
                // axis across the view. Enough to prove the quad renders.
                final double tx = player.getX() - x, tz = player.getZ() - z;
                final float len = (float) Math.sqrt(tx * tx + tz * tz);
                final float nx = len > 1e-4F ? (float) (tx / len) : 0F;
                final float nz = len > 1e-4F ? (float) (tz / len) : 1F;
                final float ux = -nz, uy = 0F, uz = nx;
                drop.setPlane(nx, 0F, nz, ux, uy, uz, 0F, 1F, 0F);
                made++;
            } else {
                bad++;
            }
        }
        out(String.format("[foliage] 强制生成 %d 个露珠，失败 %d 个，位置在玩家周围 1.6 格", made, bad));
        if (made > 0)
            out("      看得到 → 渲染没问题，问题在检测；看不到 → 粒子/贴图/注册的问题");
        else
            out("      ⚠ 一个都没生成出来：FOLIAGE_DROP 的 Provider 没注册，或 createParticle 返回了 null");
        return Component.literal(made > 0 ? "已生成 " + made + " 个测试露珠" : "生成失败");
    }

    /**
     * The snow layer's own readout: the depth channel (the ONE number - see
     * {@code PrecipitationResponse.FOLIAGE_SNOW}), the overrides, the counts.
     */
    public static Component snowStatus() {
        final FoliageSnowHandler snow = FoliageSnowHandler.INSTANCE;
        if (snow == null)
            return Component.literal("[foliage] 雪层处理器未实例化（DI 接线没跑？）");
        final var depth = org.orecruncher.dsurround.processing.weather.PrecipitationResponse.FOLIAGE_SNOW;
        out(String.format("[foliage-snow] 深度=%.2f %s  强制=%s  每株上限=%s",
                depth.value(),
                depth.isLocked() ? "(已锁定)" : "(跟随降雪时钟)",
                FoliageSnowHandler.DEBUG_FORCE,
                FoliageSnowHandler.DEBUG_UNITS > 0 ? String.valueOf(FoliageSnowHandler.DEBUG_UNITS) : "默认16"));
        out(String.format("[foliage-snow] 植物=%d 单元=%d 已挂=%d (null=%d 类型错=%d)",
                snow.plantCount(), snow.unitCount(), FoliageSnowHandler.attached,
                FoliageSnowHandler.spawnNull, FoliageSnowHandler.spawnWrong));
        out("调每株雪量: /dsdebug foliage snow <1-64>   调积雪深度: /dsprecip lock foliageSnow <0-1>（解锁 /dsprecip unlock foliageSnow）");
        return Component.literal("雪层状态（详见聊天与日志）");
    }

    /**
     * Units-per-plant override, plus the gates forced open and a rescan - the
     * command exists so the density can be exercised without waiting for a
     * storm deep enough to carry the coat on its own.
     */
    public static Component snowSet(final int units) {
        FoliageSnowHandler.DEBUG_UNITS = Math.max(1, Math.min(64, units));
        if (!FoliageSnowHandler.DEBUG_FORCE)
            FoliageSnowHandler.DEBUG_FORCE = true;
        final FoliageSnowHandler snow = FoliageSnowHandler.INSTANCE;
        if (snow != null)
            snow.forceRescan();
        out(String.format("[foliage-snow] 每株单元上限=%d，强制模式开，已重扫", FoliageSnowHandler.DEBUG_UNITS));
        out("配 /dsprecip lock foliageSnow <0-1> 可锁定积雪深度；恢复默认: /dsdebug foliage snow 16 再 /dsdebug foliage force 关强制");
        return Component.literal("雪层每株单元数已设为 " + FoliageSnowHandler.DEBUG_UNITS);
    }

    /**
     * Toggles the forced-open gate on both layers, and kicks a rescan so the
     * effect lands without waiting for the next cadence.
     */
    public static Component force() {
        final boolean on = !(FoliageRainDripHandler.DEBUG_FORCE || FoliageDewHandler.DEBUG_FORCE
                || FoliageSnowHandler.DEBUG_FORCE);
        FoliageRainDripHandler.DEBUG_FORCE = on;
        FoliageDewHandler.DEBUG_FORCE = on;
        // Snow too: without a way to force it, the layer can only be seen in a
        // cold biome that happens to be snowing. (This command exists on 1.20.1
        // only - the other two versions have no foliage diagnostics yet, which
        // is why the snow layer also answers to the config switch.)
        FoliageSnowHandler.DEBUG_FORCE = on;
        if (on) {
            // Drop the scan stamps so the very next tick rescans instead of
            // waiting out its 30s/600t cadence.
            final FoliageRainDripHandler rain = FoliageRainDripHandler.INSTANCE;
            if (rain != null)
                rain.forceRescan();
            final FoliageDewHandler dew = FoliageDewHandler.INSTANCE;
            if (dew != null)
                dew.forceRescan();
            final FoliageSnowHandler snow = FoliageSnowHandler.INSTANCE;
            if (snow != null)
                snow.forceRescan();
        } else {
            final FoliageRainDripHandler rain = FoliageRainDripHandler.INSTANCE;
            if (rain != null)
                rain.clear();
            final FoliageDewHandler dew = FoliageDewHandler.INSTANCE;
            if (dew != null)
                dew.clear();
            final FoliageSnowHandler snow = FoliageSnowHandler.INSTANCE;
            if (snow != null)
                snow.clear();
        }
        out(String.format("[foliage] 强制模式 %s（忽略下雨/晨露时间窗，直接开闸）", on ? "开" : "关"));
        return Component.literal(on ? "强制模式：开（等一两秒看效果）" : "强制模式：关");
    }

    /**
     * The config, reached through whichever handler is live - there is no
     * static accessor and the handlers are the only things that hold one.
     */
    private static Configuration config() {
        final FoliageRainDripHandler rain = FoliageRainDripHandler.INSTANCE;
        if (rain != null)
            return rain.config();
        final FoliageDewHandler dew = FoliageDewHandler.INSTANCE;
        if (dew != null)
            return dew.config();
        final FoliageSnowHandler snow = FoliageSnowHandler.INSTANCE;
        if (snow != null)
            return snow.config();
        return null;
    }

    /** ObjectArray has no stream(); the diagnostics just need a plain list. */
    private static List<FoliageScan.FoliageSpot> collect(
            final org.orecruncher.dsurround.lib.collections.ObjectArray<FoliageScan.FoliageSpot> arr) {
        final List<FoliageScan.FoliageSpot> out = new java.util.ArrayList<>(arr.size());
        for (final FoliageScan.FoliageSpot s : arr)
            out.add(s);
        return out;
    }

    /** One line to chat and to the log - the log is what survives a restart. */
    private static void out(final String line) {
        Library.LOGGER.info("[foliage-diag] %s", line);
        final LocalPlayer p = Minecraft.getInstance().player;
        if (p != null)
            p.sendSystemMessage(Component.literal(line));
    }
}
