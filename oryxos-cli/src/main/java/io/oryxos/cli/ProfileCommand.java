package io.oryxos.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Command(
        name = "profile",
        description = "Profile 管理",
        subcommands = {
                ProfileCommand.ListCommand.class,
                ProfileCommand.CreateCommand.class,
                ProfileCommand.ShowCommand.class,
                ProfileCommand.DeleteCommand.class
        }
)
public class ProfileCommand implements Runnable {

    private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

    /** ProfileLoader 同时接受 .yaml 与 .yml。 */
    private static final String[] PROFILE_SUFFIXES = {".yaml", ".yml"};

    /**
     * 敏感关键字：命中的行只输出键名，值一律遮挡。
     *
     * <p>{@code url} 也在其中——通知渠道的 webhook 地址通常内嵌凭证
     * （例如企业微信的 {@code ...?key=<密钥>}），泄漏一个地址等于交出该群组的推送权限。
     * 代价是 provider 的 {@code base-url} 也会被遮挡，可接受：它对排查问题无足轻重。</p>
     */
    private static final String[] SENSITIVE_MARKERS = {
            "key", "secret", "token", "password", "passwd", "credential", "prompt", "url"
    };

    @Override
    public void run() {
        System.out.println("profile: 使用 list/create/show/delete 子命令");
    }

    @Command(name = "list", description = "列出所有 Profile")
    static class ListCommand implements Runnable {
        @Override
        public void run() {
            WorkspaceLayout workspace = WorkspaceLayout.current();
            workspace.requireInitialized();
            try (Stream<Path> files = Files.list(workspace.profiles())) {
                files.filter(Files::isRegularFile)
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(ProfileCommand::isProfileFile)
                        .map(ProfileCommand::stripSuffix)
                        .sorted(Comparator.naturalOrder())
                        .forEach(System.out::println);
            } catch (IOException e) {
                throw new IllegalStateException("读取 Profile 失败: " + e.getMessage(), e);
            }
        }
    }

    @Command(name = "create", description = "创建 Profile")
    static class CreateCommand implements Runnable {
        @Parameters(index = "0", description = "Profile 名称")
        String name;

        @Override
        public void run() {
            validateName(name);
            WorkspaceLayout workspace = WorkspaceLayout.current();
            workspace.requireInitialized();
            Path profile = workspace.profileFile(name);
            try {
                Files.writeString(profile,
                        ProfileTemplates.defaultProfile(name, "由 oryxos profile create 创建"),
                        StandardOpenOption.CREATE_NEW);
                System.out.println("已创建 Profile: " + name + "（" + profile + "）");
                System.out.println("请编辑该文件配置 provider、tools 与 channels 后使用 oryxos chat --profile " + name);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalArgumentException("Profile 已存在: " + name);
            } catch (IOException e) {
                throw new IllegalStateException("创建 Profile 失败: " + e.getMessage(), e);
            }
        }
    }

    @Command(name = "show", description = "查看 Profile 详情")
    static class ShowCommand implements Runnable {
        @Parameters(index = "0", description = "Profile 名称")
        String name;

        @Override
        public void run() {
            validateName(name);
            WorkspaceLayout workspace = WorkspaceLayout.current();
            workspace.requireInitialized();
            Path profile = resolve(workspace, name)
                    .orElseThrow(() -> new IllegalArgumentException("Profile 不存在: " + name));
            try {
                for (String line : Files.readAllLines(profile)) {
                    if (isSensitive(line)) {
                        System.out.println(maskIndent(line));
                        continue;
                    }
                    System.out.println(line);
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取 Profile 失败: " + e.getMessage(), e);
            }
        }
    }

    @Command(name = "delete", description = "删除 Profile")
    static class DeleteCommand implements Runnable {
        @Parameters(index = "0", description = "Profile 名称")
        String name;

        @Override
        public void run() {
            validateName(name);
            if ("default".equals(name)) {
                throw new IllegalArgumentException("不能删除默认 Profile");
            }
            WorkspaceLayout workspace = WorkspaceLayout.current();
            workspace.requireInitialized();
            Path profile = resolve(workspace, name)
                    .orElseThrow(() -> new IllegalArgumentException("Profile 不存在: " + name));
            try {
                Files.delete(profile);
                // 只删配置文件，不触碰 sessions / llm_calls / tool_invocations 历史
                System.out.println("已删除 Profile: " + name + "（历史 Session 与审计记录保留）");
            } catch (IOException e) {
                throw new IllegalStateException("删除 Profile 失败: " + e.getMessage(), e);
            }
        }
    }

    /** 按 .yaml 优先查找 Profile 文件。 */
    private static Optional<Path> resolve(WorkspaceLayout workspace, String name) {
        for (String suffix : PROFILE_SUFFIXES) {
            Path candidate = workspace.profiles().resolve(name + suffix);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isProfileFile(String fileName) {
        for (String suffix : PROFILE_SUFFIXES) {
            if (fileName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static String stripSuffix(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static boolean isSensitive(String line) {
        String lower = line.toLowerCase();
        for (String marker : SENSITIVE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 保留原有缩进，隐藏值本身，避免把敏感值打进终端。 */
    private static String maskIndent(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return "  <已隐藏>";
        }
        return line.substring(0, colon + 1) + " <已隐藏>";
    }

    private static void validateName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Profile 名称非法: 仅允许字母、数字、下划线和连字符");
        }
    }
}
