package veil.vault;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Authenticated encryption helper using AES-256-GCM and PBKDF2WithHmacSHA256.
 * Binary format: [4B magic "VEIL"][1B version=1][16B salt][12B nonce][ciphertext + 16B GCM tag]
 */
public final class VaultCrypto {

    private static final byte[] MAGIC = new byte[]{'V', 'E', 'I', 'L'};
    private static final byte VERSION = 1;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 120_000;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private VaultCrypto() {
    }

    public static byte[] encrypt(byte[] plaintext, char[] passphrase) throws GeneralSecurityException {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("Vault passphrase must not be empty");
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        SECURE_RANDOM.nextBytes(nonce);

        SecretKey key = deriveKey(passphrase, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext);

        ByteBuffer buf = ByteBuffer.allocate(MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES + ciphertext.length);
        buf.put(MAGIC);
        buf.put(VERSION);
        buf.put(salt);
        buf.put(nonce);
        buf.put(ciphertext);
        return buf.array();
    }

    public static byte[] decrypt(byte[] encryptedBlob, char[] passphrase) throws GeneralSecurityException {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("Vault passphrase must not be empty");
        }
        int headerLen = MAGIC.length + 1 + SALT_BYTES + NONCE_BYTES;
        if (encryptedBlob == null || encryptedBlob.length <= headerLen + 16) {
            throw new GeneralSecurityException("Invalid or truncated vault payload");
        }
        ByteBuffer buf = ByteBuffer.wrap(encryptedBlob);
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new GeneralSecurityException("Unrecognized vault file header");
        }
        byte version = buf.get();
        if (version != VERSION) {
            throw new GeneralSecurityException("Unsupported vault format version: " + version);
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        buf.get(salt);
        buf.get(nonce);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        SecretKey key = deriveKey(passphrase, salt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        return cipher.doFinal(ciphertext);
    }

    private static SecretKey deriveKey(char[] passphrase, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
