package run.halo.bilicookie.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * AES-256-GCM 加解密工具测试。
 */
class AesGcmCipherTest {

    private static final String KEY = AesGcmCipher.generateKey();

    @Test
    void shouldRoundTripEncryptAndDecrypt() {
        AesGcmCipher cipher = new AesGcmCipher(KEY);
        String plain = "SESSDATA=abc123; bili_jct=xyz456";
        String cipherText = cipher.encrypt(plain);
        assertThat(cipherText).isNotEqualTo(plain);
        assertThat(cipher.decrypt(cipherText)).isEqualTo(plain);
    }

    @Test
    void shouldHandleUnicodeAndSpecialChars() {
        AesGcmCipher cipher = new AesGcmCipher(KEY);
        String plain = "中文/Emoji😀/符号!@#$%^&*()_+=\\{\\}[]|;:,.<>?";
        assertThat(cipher.decrypt(cipher.encrypt(plain))).isEqualTo(plain);
    }

    @Test
    void shouldEncryptEmptyString() {
        AesGcmCipher cipher = new AesGcmCipher(KEY);
        assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
    }

    @Test
    void shouldUseRandomIvPerEncryption() {
        AesGcmCipher cipher = new AesGcmCipher(KEY);
        String plain = "same-value";
        // 相同明文两次加密的密文应不同（随机 nonce）
        assertThat(cipher.encrypt(plain)).isNotEqualTo(cipher.encrypt(plain));
    }

    @Test
    void shouldFailOnTamperedCiphertext() {
        AesGcmCipher cipher = new AesGcmCipher(KEY);
        String cipherText = cipher.encrypt("secret");
        // 翻转密文中的一个字符，GCM 标签校验应失败
        String tampered = cipherText.substring(0, cipherText.length() - 1)
            + (cipherText.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> cipher.decrypt(tampered))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectInvalidKeyLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new AesGcmCipher(shortKey))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectMalformedCiphertext() {
        AesGcmCipher cipher = new AesGcmCipher(KEY);
        assertThatThrownBy(() -> cipher.decrypt("not-a-valid-cipher"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generateKeyShouldBe32Bytes() {
        byte[] keyBytes = Base64.getDecoder().decode(AesGcmCipher.generateKey());
        assertThat(keyBytes).hasSize(32);
    }
}
