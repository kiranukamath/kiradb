package io.kiradb.services.config;

import io.kiradb.core.storage.StorageEngine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only configuration store.
 *
 * <h2>Storage shape</h2>
 * For each {@code (scope, key)} pair we store a single record under storage key
 * {@code "cfg:<scope>:<key>"}. The record contains the entire history of versions
 * (oldest first). Reads return the latest version; {@code history} returns all
 * of them; rollback is a future op that appends a new version with an older value.
 *
 * <h2>Why one record per key, not per version</h2>
 * Per-version keys would let us scan history without loading the whole record,
 * but they complicate atomicity: writing a new version is two writes (the version,
 * then the bumped count) and a crash between them corrupts state. A single record
 * per key gives atomic write-the-whole-thing semantics for free, at the cost of
 * loading the whole history on read. For configuration — which is read-heavy but
 * has small history per key — that trade is correct.
 *
 * <h2>Why the listener pattern</h2>
 * Netty channel push lives in {@code kiradb-server}. The store stays Netty-free.
 * Subscribers register as {@link ConfigChangeListener}s and translate change
 * events into whatever push mechanism makes sense for their layer. This keeps
 * {@code kiradb-services} clean for any future caller (Java SDK, dashboard, etc.).
 */
public final class ConfigStore {

    private static final String KEY_PREFIX = "cfg:";
    private static final byte FORMAT_VERSION = 1;

    private final StorageEngine storage;
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Construct a store backed by the given storage engine.
     *
     * @param storage storage engine for persistence
     */
    public ConfigStore(final StorageEngine storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Register a listener to be notified on every successful change.
     *
     * @param listener the listener
     */
    public void addListener(final ConfigChangeListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Unregister a previously added listener.
     *
     * @param listener the listener
     */
    public void removeListener(final ConfigChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Set or update a config value. Always appends a new version. Notifies listeners.
     *
     * @param scope the configuration scope
     * @param key   the configuration key
     * @param value the new value
     * @return the new version's metadata
     */
    public ConfigVersion set(final String scope, final String key, final String value) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");

        byte[] storageKey = storageKey(scope, key);
        synchronized (this) {  // serialize concurrent writes to the same record
            List<ConfigVersion> versions = new ArrayList<>(loadVersions(storageKey));
            long nextNumber = versions.isEmpty()
                    ? 1L : versions.get(versions.size() - 1).versionNumber() + 1;
            ConfigVersion next = new ConfigVersion(
                    nextNumber, System.currentTimeMillis(), value);
            versions.add(next);
            storage.put(storageKey, serialize(versions));
            notifyListeners(new ConfigChange(scope, key, next));
            return next;
        }
    }

    /**
     * @param scope the scope
     * @param key   the key
     * @return latest value, or empty if absent
     */
    public Optional<String> get(final String scope, final String key) {
        List<ConfigVersion> versions = loadVersions(storageKey(scope, key));
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(versions.get(versions.size() - 1).value());
    }

    /**
     * @param scope the scope
     * @param key   the key
     * @return latest version with metadata, or empty if absent
     */
    public Optional<ConfigVersion> latestVersion(final String scope, final String key) {
        List<ConfigVersion> versions = loadVersions(storageKey(scope, key));
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(versions.get(versions.size() - 1));
    }

    /**
     * @param scope the scope
     * @param key   the key
     * @return all versions in order, oldest first; empty if absent
     */
    public List<ConfigVersion> history(final String scope, final String key) {
        return loadVersions(storageKey(scope, key));
    }

    /**
     * Roll back {@code (scope, key)} to the value it held {@code versionsBack}
     * versions ago, by appending that old value as a brand-new version.
     *
     * <p>This is intentionally non-destructive: history is append-only (see class
     * Javadoc), so a rollback is itself a new audit-trail entry rather than a
     * deletion or rewrite of prior versions. A rollback of {@code versionsBack = 1}
     * restores the value from immediately before the current one; {@code 0} would
     * "roll back" to the current value itself (a no-op value-wise, but it still
     * appends a new version — callers wanting a true no-op should check first).
     *
     * @param scope        the scope
     * @param key          the key
     * @param versionsBack how many versions back to read from (must be {@code >= 0}
     *                     and no greater than the number of versions currently on
     *                     record, i.e. it must address an existing version)
     * @return the newly appended version, or empty if {@code versionsBack} does not
     *         address an existing version (including the case where the key has
     *         no history at all)
     */
    public Optional<ConfigVersion> rollback(
            final String scope, final String key, final int versionsBack) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        if (versionsBack < 0) {
            return Optional.empty();
        }

        byte[] storageKey = storageKey(scope, key);
        synchronized (this) {  // serialize with concurrent set()/rollback() on the same record
            List<ConfigVersion> versions = new ArrayList<>(loadVersions(storageKey));
            int targetIndex = versions.size() - 1 - versionsBack;
            if (targetIndex < 0 || targetIndex >= versions.size()) {
                return Optional.empty();
            }
            String targetValue = versions.get(targetIndex).value();

            long nextNumber = versions.get(versions.size() - 1).versionNumber() + 1;
            ConfigVersion next = new ConfigVersion(
                    nextNumber, System.currentTimeMillis(), targetValue);
            versions.add(next);
            storage.put(storageKey, serialize(versions));
            notifyListeners(new ConfigChange(scope, key, next));
            return Optional.of(next);
        }
    }

    private void notifyListeners(final ConfigChange change) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onChange(change);
            } catch (RuntimeException e) {
                // Listener exceptions must not break the writer — log-and-continue.
                // (Logger omitted to avoid pulling slf4j into the unit test path; this
                // listener path is exercised in CFG integration tests where bad
                // listeners would surface as test failures.)
            }
        }
    }

    private List<ConfigVersion> loadVersions(final byte[] storageKey) {
        Optional<byte[]> bytes = storage.get(storageKey);
        if (bytes.isEmpty()) {
            return Collections.emptyList();
        }
        return deserialize(bytes.get());
    }

    private static byte[] storageKey(final String scope, final String key) {
        return (KEY_PREFIX + scope + ":" + key).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] serialize(final List<ConfigVersion> versions) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(FORMAT_VERSION);
            out.writeInt(versions.size());
            for (ConfigVersion v : versions) {
                out.writeLong(v.versionNumber());
                out.writeLong(v.timestampMillis());
                out.writeUTF(v.value());
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<ConfigVersion> deserialize(final byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unknown ConfigStore format version: " + version);
            }
            int count = in.readInt();
            List<ConfigVersion> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long verNum = in.readLong();
                long ts = in.readLong();
                String val = in.readUTF();
                out.add(new ConfigVersion(verNum, ts, val));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
