package com.evidence.rag.worker.indexing;

import com.evidence.rag.client.vector.MilvusRestProjection;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Cross-JVM writer admission and parent lifetime; physical generations fence late remote writes.
 */
final class IndexWorkerLifetime implements AutoCloseable {
  // A fixed stripe count bounds memory. Same-target callers must not open a second channel:
  // on POSIX, closing that channel may release another caller's process-wide file lock.
  private static final Semaphore[] LOCAL_ADMISSION = localAdmission();

  private static Semaphore[] localAdmission() {
    var stripes = new Semaphore[64];
    for (int i = 0; i < stripes.length; i++) {
      stripes[i] = new Semaphore(1);
    }
    return stripes;
  }

  record Parent(long pid, Instant started) {
    Parent {
      if (pid <= 0 || started == null) {
        throw failure("indexing_parent_lost");
      }
    }

    static Parent current() {
      var process = ProcessHandle.current();
      return new Parent(
          process.pid(),
          process.info().startInstant().orElseThrow(() -> failure("indexing_parent_lost")));
    }

    boolean alive() {
      return ProcessHandle.of(pid)
          .filter(ProcessHandle::isAlive)
          .flatMap(process -> process.info().startInstant())
          .filter(started::equals)
          .isPresent();
    }
  }

  private final Parent parent;
  private final long deadline;
  private final boolean isolated;
  private final Thread caller = Thread.currentThread();
  private final Object watchLock = new Object();
  private volatile boolean closed;
  private Thread watchdog;
  private FileChannel channel;
  private FileLock lease;
  private Semaphore localPermit;

  private IndexWorkerLifetime(Parent parent, Duration timeout, boolean isolated) {
    this.parent = parent;
    this.deadline = System.nanoTime() + timeout.toNanos();
    this.isolated = isolated;
  }

  static IndexWorkerLifetime acquire(
      MilvusRestProjection.Settings settings, Parent parent, Duration timeout, boolean isolated) {
    var lifetime = new IndexWorkerLifetime(parent, timeout, isolated);
    try {
      // Only main owns process termination. Public run remains a normal, testable JVM Interface.
      if (isolated
          && ProcessHandle.current()
              .parent()
              .map(ProcessHandle::pid)
              .filter(pid -> pid == parent.pid())
              .isEmpty()) {
        throw failure("indexing_parent_lost");
      }
      lifetime.check();
      lifetime.watchdog =
          Thread.ofPlatform().name("index-parent-lifetime").daemon(true).start(lifetime::watch);
      Path file = leaseFile(settings);
      var admission = LOCAL_ADMISSION[Math.floorMod(file.hashCode(), LOCAL_ADMISSION.length)];
      while (lifetime.localPermit == null) {
        lifetime.check();
        if (admission.tryAcquire(25, TimeUnit.MILLISECONDS)) {
          lifetime.localPermit = admission;
        }
      }
      lifetime.check();
      lifetime.channel =
          FileChannel.open(
              file,
              Set.of(
                  StandardOpenOption.CREATE,
                  StandardOpenOption.READ,
                  StandardOpenOption.WRITE,
                  LinkOption.NOFOLLOW_LINKS),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      validate(file, false);
      while (lifetime.lease == null) {
        lifetime.check();
        try {
          lifetime.lease = lifetime.channel.tryLock();
        } catch (OverlappingFileLockException busy) {
          /* Same-JVM run callers share this same admission rule. */
        }
        if (lifetime.lease == null) {
          Thread.sleep(25);
        }
      }
      lifetime.check();
      return lifetime;
    } catch (IOException | InterruptedException | RuntimeException failure) {
      lifetime.close();
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      if (!parent.alive()) {
        throw failure("indexing_parent_lost");
      }
      if (System.nanoTime() >= lifetime.deadline) {
        throw failure("indexing_timeout");
      }
      if (Thread.currentThread().isInterrupted()) {
        throw failure("worker_interrupted");
      }
      if (failure instanceof ProcessTextIndexer.Failure safe) {
        throw safe;
      }
      throw failure("indexing_lifetime_failed");
    }
  }

  void check() {
    if (!parent.alive()) {
      throw failure("indexing_parent_lost");
    }
    if (System.nanoTime() >= deadline) {
      throw failure("indexing_timeout");
    }
    if (Thread.currentThread().isInterrupted()) {
      throw failure("worker_interrupted");
    }
  }

  private void watch() {
    try {
      while (!closed) {
        synchronized (watchLock) {
          if (closed) {
            return;
          }
          if (!parent.alive() || System.nanoTime() >= deadline) {
            if (isolated) {
              Runtime.getRuntime().halt(70);
            }
            caller.interrupt();
            return;
          }
        }
        Thread.sleep(25);
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private static Path leaseFile(MilvusRestProjection.Settings settings) throws IOException {
    // Stable across application restart and independent of both application data and job tmpdir.
    // POSIX is an explicit local-runtime requirement; unsupported filesystems fail closed.
    UserPrincipal owner = currentOwner(Path.of("/tmp"));
    Path root =
        Path.of("/tmp")
            .toRealPath()
            .resolve("evidence-rag-index-leases-v1-" + hash(owner.getName()));
    try {
      Files.createDirectory(
          root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (FileAlreadyExistsException exists) {
      /* Never replace an existing inode. Validate it. */
    }
    validate(root, true);
    var endpoint = settings.endpoint();
    String scheme = endpoint.getScheme().toLowerCase(Locale.ROOT);
    int port = endpoint.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : endpoint.getPort();
    String target =
        scheme
            + "\n"
            + endpoint.getHost().toLowerCase(Locale.ROOT)
            + "\n"
            + port
            + "\n"
            + settings.database()
            + "\n"
            + settings.collection();
    return root.resolve(hash(target) + ".lease");
  }

  private static UserPrincipal currentOwner(Path path) throws IOException {
    return path.getFileSystem()
        .getUserPrincipalLookupService()
        .lookupPrincipalByName(System.getProperty("user.name"));
  }

  private static void validate(Path path, boolean directory) throws IOException {
    var attributes =
        Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.owner().equals(currentOwner(path))
        || !attributes
            .permissions()
            .equals(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))
        || (directory
            ? !attributes.isDirectory()
            : !attributes.isRegularFile() || attributes.size() != 0)
        || attributes.isSymbolicLink()) {
      throw new IOException();
    }
    if (!directory
        && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue()
            != 1) {
      throw new IOException();
    }
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw failure("indexing_lifetime_failed");
    }
  }

  @Override
  public void close() {
    synchronized (watchLock) {
      if (closed) {
        return;
      }
      closed = true;
    }
    if (watchdog != null) {
      watchdog.interrupt();
    }
    // Never delete a lock file: unlinking a held inode permits a second independent lock.
    try {
      if (lease != null) {
        lease.close();
      }
    } catch (IOException ignored) {
      /* Closing the channel below releases its remaining locks. */
    }
    boolean channelClosed = channel == null;
    try {
      if (channel != null) {
        channel.close();
      }
      channelClosed = true;
    } catch (IOException ignored) {
      /* Unconfirmed descriptor closure keeps local admission closed. */
    }
    if (channelClosed && localPermit != null) {
      localPermit.release();
    }
  }

  private static ProcessTextIndexer.Failure failure(String code) {
    return new ProcessTextIndexer.Failure(code);
  }
}
