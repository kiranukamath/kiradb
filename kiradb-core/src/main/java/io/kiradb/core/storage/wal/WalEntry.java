package io.kiradb.core.storage.wal;

/**
 * One entry in the Write-Ahead Log.
 *
 * <p>Binary wire format (written to disk in this order):
 * <pre>
 *   [1 byte ] op           — 0x01=PUT, 0x02=DELETE
 *   [8 bytes] timestamp    — epoch millis
 *   [8 bytes] expiryMillis — absolute expiry, -1 = no expiry
 *   [4 bytes] keyLen
 *   [keyLen ] key bytes
 *   [4 bytes] valueLen     — 0 for DELETE entries
 *   [valueLen] value bytes
 *   [4 bytes] crc32        — CRC32 of all preceding bytes in this entry
 * </pre>
 *
 * @param op           operation type
 * @param timestamp    wall-clock time of the write (epoch millis)
 * @param expiryMillis absolute expiry epoch-millis; {@code -1} means no expiry
 * @param key          the key bytes
 * @param value        the value bytes; empty array for DELETE entries
 * @param crc32        CRC32 checksum of all fields above this one
 */
public record WalEntry(
        Op op,
        long timestamp,
        long expiryMillis,
        byte[] key,
        byte[] value,
        int crc32) {

    /** WAL operation types. */
    public enum Op {
        /** Store a key-value pair. */
        PUT((byte) 0x01),
        /** Mark a key as deleted (tombstone). */
        DELETE((byte) 0x02);

        private final byte code;

        Op(final byte code) {
            this.code = code;
        }

        /**
         * Wire byte for this operation.
         *
         * @return the single-byte op code
         */
        public byte code() {
            return code;
        }

        /**
         * Decode an op from its wire byte.
         *
         * @param code the byte read from the WAL file
         * @return the matching Op
         * @throws IllegalArgumentException if the byte is not a known op code
         */
        public static Op fromCode(final byte code) {
            for (Op op : values()) {
                if (op.code == code) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Unknown WAL op code: 0x" + Integer.toHexString(code & 0xFF));
        }
    }
}
