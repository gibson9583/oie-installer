package org.openintegrationengine.installer;

import com.sun.nio.file.ExtendedOpenOption;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Checks the compiled deletion code using temporary files, without an engine. */
public final class FileCleanupCheck {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void readOnly(Path file) throws IOException {
        if (Model.WINDOWS) Files.setAttribute(file, "dos:readonly", true, LinkOption.NOFOLLOW_LINKS);
        else Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--r--r--"));
    }
    public static void main(String[] args) throws Exception {
        Path work = Files.createDirectory(Path.of(args[0]));
        try {
            Path tree = Files.createDirectory(work.resolve("engine with spaces"));
            Path nested = Files.createDirectories(tree.resolve("app/nested"));
            Files.writeString(nested.resolve("launcher.cfg"), "configuration");
            Path launcher = Files.writeString(tree.resolve("oiecommand.exe"), "read-only launcher");
            readOnly(launcher);
            readOnly(nested.resolve("launcher.cfg"));
            if (Model.WINDOWS) Files.setAttribute(nested, "dos:readonly", true);
            FilesSupport.deleteTree(tree);
            require(!Files.exists(tree), "Read-only files prevented recursive deletion");
            FilesSupport.deleteTree(tree); // A repeated cleanup must succeed.
            System.out.println("PASS: recursive read-only deletion and repeated cleanup");

            Path single = Files.writeString(work.resolve("single.exe"), "launcher");
            readOnly(single);
            FilesSupport.deleteTree(single);
            require(!Files.exists(single), "Read-only root file remains");
            System.out.println("PASS: read-only file as the deletion root");

            Path target = Files.createDirectory(work.resolve("retained"));
            Path marker = Files.writeString(target.resolve("marker"), "keep");
            readOnly(marker);
            tree = Files.createDirectory(work.resolve("linked tree"));
            boolean linked = false;
            try { Files.createSymbolicLink(tree.resolve("link"), target); linked = true; }
            catch (UnsupportedOperationException | FileSystemException unsupported) {
                if (!Model.WINDOWS) throw unsupported;
                System.out.println("SKIP: symlink creation is unavailable on this Windows host");
            }
            FilesSupport.deleteTree(tree);
            require(Files.readString(marker).equals("keep"), "Deletion followed a symbolic link");
            if (Model.WINDOWS) require((Boolean)Files.getAttribute(marker, "dos:readonly"), "Deletion changed a linked target's attributes");
            if (linked) System.out.println("PASS: symbolic link target and attributes remain unchanged");

            if (Model.WINDOWS) {
                for (boolean flagged : List.of(false, true)) {
                    tree = Files.createDirectory(work.resolve("locked-" + flagged));
                    Path locked = Files.writeString(tree.resolve("locked.exe"), "owned but open");
                    if (flagged) readOnly(locked);
                    try (var handle = Files.newByteChannel(locked, Set.of(StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE))) {
                        try { FilesSupport.deleteTree(tree); throw new AssertionError("Deleted a locked file"); }
                        catch (FileSystemException expected) {
                            require(Files.exists(locked), "The locked file disappeared");
                            require((Boolean)Files.getAttribute(locked, "dos:readonly") == flagged, "Failed deletion changed the read-only flag");
                        }
                    }
                    FilesSupport.deleteTree(tree);
                    require(!Files.exists(tree), "Retry failed after the file handle closed");
                }
                System.out.println("PASS: locked files fail safely; retry succeeds after closing the handle");
            } else System.out.println("SKIP: Windows file locking and DOS read-only checks");
        } finally {
            // Independent cleanup also runs if the implementation under test fails.
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (Model.WINDOWS && !Files.isSymbolicLink(path)) Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
                    Files.delete(path);
                }
            }
        }
    }
}
