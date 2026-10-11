package org.orecruncher.dsurround.mixins.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import org.orecruncher.dsurround.compat.DataPackMigration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 世界加载时的数据包改名迁移（见 {@link DataPackMigration}）。
 *
 * <p>注入点选在 {@code MinecraftServer.configurePackRepository} 的 <b>HEAD</b>：
 * 这是"存档写下的启用表"与"最终选中的包"之间唯一一个两边都能看到的位置 ——</p>
 * <ul>
 *   <li>HEAD 时 {@code initialDataConfig.dataPacks().getEnabled()} 还是
 *       {@code level.dat} 里那份原始名单（含 {@code mod:dsurround}），
 *       NeoForge 的 {@code DataPackConfig.addModPacks(CommonHooks.getModDataPacks())}
 *       还没跑；注入到此为止之后，NeoForge 的自动补包与选包逻辑照常执行，
 *       我们只是<b>多写进一条 {@code mod:dsrebirth}</b>，不替换、不删除、
 *       不改变其它任何一项。</li>
 *   <li>若注入到 RETURN：那时 {@code mod:dsurround} 已被
 *       {@code getSelectedPacks()} 过滤掉，条件再也无法判定，等于什么都没做。</li>
 * </ul>
 *
 * <p><b>require = 0</b>：两个 NeoForge 版与 1.20.1 的 Forge 版这个方法签名不同
 * （这里也跟着各版改写），万一某个未来版本又改签名，注入失败只是静默跳过，
 * 绝不让 {@code "required": true} 把启动打死 —— 这与本模组其它 mixin 的纪律一致。</p>
 */
@Mixin(MinecraftServer.class)
public final class MixinMinecraftServerDataPacks {

    @Inject(
            method = "configurePackRepository(Lnet/minecraft/server/packs/repository/PackRepository;"
                    + "Lnet/minecraft/world/level/WorldDataConfiguration;ZZ)"
                    + "Lnet/minecraft/world/level/WorldDataConfiguration;",
            at = @At("HEAD"),
            require = 0)
    private static void dsrebirth$migrateDataPackId(final PackRepository repository,
                                                    final WorldDataConfiguration initialDataConfig,
                                                    final boolean initMode,
                                                    final boolean safeMode,
                                                    final CallbackInfoReturnable<WorldDataConfiguration> cir) {
        // NeoForge：存档的 DataPackConfig 挂在 WorldDataConfiguration 里。
        DataPackMigration.onConfigurePackRepository(
                repository,
                initialDataConfig == null ? null : initialDataConfig.dataPacks());
    }
}
