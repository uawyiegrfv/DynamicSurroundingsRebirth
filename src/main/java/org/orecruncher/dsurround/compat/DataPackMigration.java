package org.orecruncher.dsurround.compat;

import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.DataPackConfig;
import org.orecruncher.dsurround.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 改名兼容：把老存档 level.dat 里记着的旧数据包 id 补成新的。
 *
 * <p>背景：mod id 从 {@code dsurround} 改名成 {@code dsrebirth} 之后，
 * 老存档的 {@code level.dat} 的 {@code DataPackConfig.Enabled} 里写的仍然是
 * {@code mod:dsurround}。加载器会在日志里报一条
 * {@code Missing data pack mod:dsurround}（那条警告本身是无害的：老 id 对应的包
 * 早就不存在了），但存档里<b>没有</b>任何一条指向 {@code mod:dsrebirth} 的记录，
 * 于是一旦加载器将来不再替我们把新装模组的数据包自动加进启用表，模组自带的
 * {@code assets/<ns>/dsconfigs}、{@code data/<ns>/tags} 就整个读不到
 * （表现出来就是 {@code Data self-check ... unresolvable block in a rule}
 * 与植物积雪/露水全部不工作）。这里补一条显式记录，把这件事变成确定行为。</p>
 *
 * <p>做法（三版一致，与加载器无关）：</p>
 * <ul>
 *   <li>只看<b>存档自己写下的</b>启用表：含 {@code mod:dsurround} 且不含
 *       {@code mod:dsrebirth} 时才动手；</li>
 *   <li>把 {@code mod:dsrebirth} <b>追加</b>进去，旧条目<b>保留不动</b>
 *       （不删旧 id —— 那道 {@code Missing data pack} 警告是"这个存档以前装过它"
 *       的唯一记录，删掉反而是毁证据）；</li>
 *   <li>已经含有新 id（新存档、或迁移过一次的存档）时什么都不做，因此可重复调用。</li>
 * </ul>
 *
 * <p><b>改的是哪一层</b>：内存里这一份 {@link DataPackConfig}（也就是本次加载用的
 * "启用表"）。它随后会被 {@code configurePackRepository} 用来选包，并被
 * {@code PrimaryLevelData} 记进 {@code WorldDataConfiguration}，在存档保存时
 * 由 vanilla 自己写回 {@code level.dat}。本类<b>不会</b>直接读写 {@code level.dat}，
 * 也不碰 {@code server.properties}（那里根本没有数据包开关）。</p>
 *
 * <p><b>fail-closed</b>：任何异常都只留下一条 WARN，绝不向调用方抛出 ——
 * 迁移失败最坏的结果就是回到"和没打这个补丁一样"，绝不允许因此阻止世界加载。</p>
 */
public final class DataPackMigration {

    /** 改名前的数据包 id（旧 mod id）。 */
    private static final String LEGACY_PACK_ID = "mod:dsurround";

    /** 改名后的数据包 id。用 {@link Constants#MOD_ID} 拼，mod id 再改时只改一处。 */
    private static final String CURRENT_PACK_ID = "mod:" + Constants.MOD_ID;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(Constants.MOD_ID + "/DataPackMigration");

    private DataPackMigration() {
    }

    /**
     * 在世界/服务器加载、加载器正要按存档的启用表选包之前调用一次。
     *
     * @param repository 本次加载的包仓库；只用来确认新包真的在盘上（可为 null）
     * @param config     存档写下的数据包配置；直接就地追加
     */
    public static void onConfigurePackRepository(final PackRepository repository,
                                                 final DataPackConfig config) {
        try {
            if (config == null)
                return;

            final List<String> enabled = config.getEnabled();
            if (enabled == null)
                return;

            // 已经有新 id：新存档，或这个存档上次已经迁移过。什么都不做。
            if (enabled.contains(CURRENT_PACK_ID))
                return;

            // 连旧 id 都没有：不是我们的存档，不插手。
            if (!enabled.contains(LEGACY_PACK_ID))
                return;

            // 只补一个确实存在的包。补一个不存在的名字只会换来一条
            // "Missing data pack"，那是噪音，不是修复。仓库此刻可能还没 reload()
            // （HEAD 注入点），所以"查不到"不等于"不存在" —— 这种情况交给加载器
            // 自己的自动添加逻辑，我们只记一行。
            if (repository != null && !repository.isAvailable(CURRENT_PACK_ID)) {
                LOGGER.warn("Data pack migration: this world still lists '{}' but '{}' is not"
                                + " available in the pack repository yet - leaving the selection alone.",
                        LEGACY_PACK_ID, CURRENT_PACK_ID);
                return;
            }

            // 追加，不替换：旧条目原样保留。
            config.addModPacks(List.of(CURRENT_PACK_ID));

            LOGGER.info("Data pack migration: world listed the pre-rename id '{}' and not '{}';"
                            + " added '{}' to the enabled list (the old entry is kept).",
                    LEGACY_PACK_ID, CURRENT_PACK_ID, CURRENT_PACK_ID);
        } catch (final Throwable t) {
            // fail-closed: 一行日志，绝不阻止加载。
            LOGGER.warn("Data pack migration skipped ({}: {})",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }
}
