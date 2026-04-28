package io.kiradb.services.flags;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Immutable snapshot of a feature flag's configuration.
 *
 * <p>The two interesting fields are {@link #killed} and {@link #rolloutPercent}.
 * {@code killed} is the always-off override — when true, the flag is OFF for everyone
 * regardless of rollout. {@code rolloutPercent} is the fraction of users for whom the
 * flag is enabled (0.0 = nobody, 1.0 = everyone). Each user's bucket is computed
 * deterministically from {@code (flagName, userId)} so the same user always gets the
 * same answer — this prevents UI flicker.
 *
 * <p>Stored on disk as the value of an {@link io.kiradb.crdt.LWWRegister} keyed by flag name.
 * Concurrency between admins is resolved by LWW. Operators race on flag updates
 * approximately never, and "last admin wins" matches the mental model.
 *
 * @param name           flag identifier
 * @param killed         when true, flag is OFF unconditionally
 * @param rolloutPercent fraction of users for whom the flag is ON, in [0.0, 1.0]
 */
public record FeatureFlag(String name, boolean killed, double rolloutPercent) {

    private static final byte FORMAT_VERSION = 1;

    /**
     * Validating constructor. Clamps rollout to [0, 1].
     */
    public FeatureFlag {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("flag name must be non-blank");
        }
        if (rolloutPercent < 0.0 || rolloutPercent > 1.0) {
            throw new IllegalArgumentException(
                    "rolloutPercent must be in [0.0, 1.0], was " + rolloutPercent);
        }
    }

    /**
     * @param name flag name
     * @return a flag fully enabled (rollout 100%, not killed)
     */
    public static FeatureFlag fullyEnabled(final String name) {
        return new FeatureFlag(name, false, 1.0);
    }

    /**
     * @param name flag name
     * @return a flag fully disabled (rollout 0%, not killed)
     */
    public static FeatureFlag fullyDisabled(final String name) {
        return new FeatureFlag(name, false, 0.0);
    }

    /**
     * @return a copy with {@code killed=true}; rollout retained for un-kill restore
     */
    public FeatureFlag asKilled() {
        return new FeatureFlag(name, true, rolloutPercent);
    }

    /**
     * @return a copy with {@code killed=false}, rollout restored to the prior value
     */
    public FeatureFlag asUnkilled() {
        return new FeatureFlag(name, false, rolloutPercent);
    }

    /**
     * @param newPercent new rollout fraction in [0.0, 1.0]
     * @return a copy with the updated rollout percent
     */
    public FeatureFlag withRollout(final double newPercent) {
        return new FeatureFlag(name, killed, newPercent);
    }

    /**
     * Serialize to a compact binary form for storage.
     *
     * <p>Format: {@code [version:byte] [killed:byte] [rolloutPercent:double] [name:utf]}.
     *
     * @return wire bytes
     */
    public byte[] serialize() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(FORMAT_VERSION);
            out.writeByte(killed ? 1 : 0);
            out.writeDouble(rolloutPercent);
            out.writeUTF(name);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reconstruct from serialized bytes.
     *
     * @param bytes output of {@link #serialize()}
     * @return reconstructed flag
     */
    public static FeatureFlag deserialize(final byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            byte version = in.readByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalStateException(
                        "Unknown FeatureFlag format version: " + version);
            }
            boolean killed = in.readByte() == 1;
            double rollout = in.readDouble();
            String name = in.readUTF();
            return new FeatureFlag(name, killed, rollout);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
