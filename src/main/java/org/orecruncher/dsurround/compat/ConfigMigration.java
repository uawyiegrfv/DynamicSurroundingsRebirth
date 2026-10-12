package org.orecruncher.dsurround.compat;

import org.orecruncher.dsurround.Constants;
import org.orecruncher.dsurround.lib.platform.ModInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 改名兼容：把 config/dsurround/ 下改名前的配置文件搬成 config/dsrebirth/ 的。
 *
 * <p>背景：mod id 从 {@code dsurround} 改名成 {@code dsrebirth} 之后，配置路径是由
 * {@code Constants.MOD_ID} 推出来的（{@code @ConfigPlacement(folderName = Constants.MOD_ID,
 * fileName = Constants.MOD_ID)} -> {@code <configDir>/<modId>/<modId>.json}），于是它从
 * {@code config/dsurround/dsurround.json} 变成了 {@code config/dsrebirth/dsrebirth.json}。
 * 改名只动了常量，没有人搬过文件：老玩家的设置在盘上原样躺着，模组却再也读不到它 ——
 * 表现出来就是"升级后所有设置被静默重置"（新路径下生成一份全默认的文件，旧文件成了孤儿）。
 * 受影响的<b>不止</b>主配置：同目录的 {@code soundconfig.json}（逐音效的屏蔽/剔除/音量覆盖）
 * 走的是同一个目录，同样是孤儿。</p>
 *
 * <p>做法（三版一致，与加载器无关）：</p>
 * <ul>
 *   <li>旧文件在、新文件不在：把旧文件<b>复制</b>成新文件；</li>
 *   <li>旧文件不在：什么都不做，<b>也不打日志</b>（绝大多数玩家属于这一种，不该有噪音）；</li>
 *   <li>新旧同时存在：<b>不合并</b>，新文件一个字节都不动，只留一行 INFO 说明旧文件被忽略。
 *       老玩家只要启动过一次改名后的版本，盘上就已经是新旧并存了；此时以新文件为准是唯一
 *       不会造出"第二份真实来源"的选择。注意这一行在旧文件被删掉之前<b>每次启动都会出现</b>，
 *       它说的就是这件事；</li>
 *   <li>旧文件<b>保留不动</b>：是复制不是移动，回退到旧版本时设置还在，也留下排查的凭据；</li>
 *   <li><b>不做双读</b>：迁移之后旧路径不再被读取，配置只有一个真实来源。</li>
 * </ul>
 *
 * <p><b>只做一次</b>：进程内由 {@link #attempted} 保证；跨进程由"新文件是否存在"保证
 * （复制成功后新文件就存在了，下一次启动走的是"新旧同时存在"那一支）。因此本方法是幂等的，
 * 重复调用没有任何副作用。</p>
 *
 * <p><b>只在客户端启动路径上调用</b>：调用点是 {@code Client} 的构造函数里、读
 * {@link org.orecruncher.dsurround.Configuration} 之前的第一件事。{@code Client} 只在
 * 客户端的 {@code dist.isClient()} 分支里被 new 出来，专用服务端与数据生成（runData）都走不到
 * 这里 —— 那些路径上的配置该由它们自己按默认值生成，不该被客户端的历史文件影响。</p>
 *
 * <p><b>fail-open</b>：任何异常都只留下一行 WARN，绝不向调用方抛出，也绝不阻止启动 ——
 * 迁移失败最坏的结果就是回到"和没打这个补丁一样"。</p>
 */
public final class ConfigMigration {

    /** 改名前的配置目录名（旧 mod id）。这是历史，不能由 {@link Constants#MOD_ID} 推。 */
    private static final String LEGACY_FOLDER = "dsurround";

    /** 改名前的配置文件基名。 */
    private static final String LEGACY_CONFIG_FILE = LEGACY_FOLDER + ".json";

    /** 当前配置文件基名。用 {@link Constants#MOD_ID} 拼，mod id 再改时只改一处。 */
    private static final String CURRENT_CONFIG_FILE = Constants.MOD_ID + ".json";

    /** 同目录下的第二份配置：逐音效的屏蔽/剔除/音量覆盖。 */
    private static final String SOUND_CONFIG_FILE = "soundconfig.json";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(Constants.MOD_ID + "/ConfigMigration");

    /** 进程内只跑一次。 */
    private static boolean attempted;

    private ConfigMigration() {
    }

    /**
     * 在客户端启动、读配置之前调用一次。重复调用无副作用。
     */
    public static synchronized void onClientStartup() {
        if (attempted)
            return;
        attempted = true;

        try {
            final Path currentFolder = ModInformation.getConfigPath(Constants.MOD_ID);

            // getConfigPath 在建目录失败时会退回配置根目录本身（见 ModInformation）。
            // 那时它就不再是 ".../dsrebirth"，resolveSibling 会指到一个不存在的地方；与其猜，
            // 不如什么都不做 —— 配置根目录下刚好有个叫 dsurround 的目录这种事并不值得赌。
            final Path leaf = currentFolder.getFileName();
            if (leaf == null || !Constants.MOD_ID.equals(leaf.toString())) {
                LOGGER.warn("Config migration skipped: '{}' is not the '{}' config folder.",
                        currentFolder, Constants.MOD_ID);
                return;
            }

            final Path legacyFolder = currentFolder.resolveSibling(LEGACY_FOLDER);

            copyIfAbsent(legacyFolder.resolve(LEGACY_CONFIG_FILE),
                    currentFolder.resolve(CURRENT_CONFIG_FILE));
            copyIfAbsent(legacyFolder.resolve(SOUND_CONFIG_FILE),
                    currentFolder.resolve(SOUND_CONFIG_FILE));
        } catch (final Throwable t) {
            // fail-open: 一行日志，绝不阻止启动。
            LOGGER.warn("Config migration skipped ({}: {})",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /**
     * 旧文件在、新文件不在时复制一份；其余情况什么都不动。
     *
     * @param legacy  改名前的文件
     * @param current 现在的文件
     */
    private static void copyIfAbsent(final Path legacy, final Path current) {
        try {
            // 没有旧文件：最常见的情况，一个字都不说。
            if (!Files.isRegularFile(legacy))
                return;

            if (Files.exists(current)) {
                LOGGER.info("Config migration: '{}' already exists, so the pre-rename file '{}'"
                                + " is ignored (not merged, not deleted).",
                        current, legacy);
                return;
            }

            final Path parent = current.getParent();
            if (parent != null)
                Files.createDirectories(parent);

            try {
                // 不加 REPLACE_EXISTING：万一目标在这两行之间被别的东西建出来，
                // 宁可放弃也不覆盖。
                Files.copy(legacy, current);
            } catch (final FileAlreadyExistsException raced) {
                // 只有 Files.copy 抛的这个才真的是"别人抢先建了目标"。外层那个
                // createDirectories 也会抛同名异常（目标位置被一个普通文件占着），
                // 那是 IO 失败，必须落到下面的 WARN，不能被这里吞成 INFO。
                LOGGER.info("Config migration: '{}' appeared while migrating, so the pre-rename"
                                + " file '{}' is ignored.", current, legacy);
                return;
            }

            LOGGER.info("Config migration: copied the pre-rename file '{}' to '{}'"
                            + " (the old file is kept).",
                    legacy, current);
        } catch (final Throwable t) {
            LOGGER.warn("Config migration: unable to copy '{}' to '{}' ({}: {}).",
                    legacy, current, t.getClass().getSimpleName(), t.getMessage());
        }
    }
}
