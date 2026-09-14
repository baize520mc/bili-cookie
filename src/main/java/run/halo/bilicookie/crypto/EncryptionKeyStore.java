package run.halo.bilicookie.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

/**
 * 加密密钥存储：使用 Halo {@link Secret} 持久化 AES 密钥。
 *
 * <p>首次使用时自动生成 32 字节密钥并创建 Secret，后续读取复用。</p>
 */
@Component
public class EncryptionKeyStore {

    public static final String SECRET_NAME = "bili-cookie-encryption-key";
    private static final String DATA_KEY = "key";

    private final ReactiveExtensionClient client;

    public EncryptionKeyStore(ReactiveExtensionClient client) {
        this.client = client;
    }

    /** 获取当前密钥对应的加解密工具，密钥不存在则自动生成并持久化。 */
    public Mono<AesGcmCipher> getOrCreateCipher() {
        return getOrCreateKey().map(AesGcmCipher::new);
    }

    private Mono<String> getOrCreateKey() {
        return client.fetch(Secret.class, SECRET_NAME)
            .mapNotNull(this::extractKey)
            .switchIfEmpty(createAndReturnKey());
    }

    private String extractKey(Secret secret) {
        // data 为 byte[]，优先读取；stringData 仅在特殊情况下兜底。
        if (secret.getData() != null) {
            byte[] bytes = secret.getData().get(DATA_KEY);
            if (bytes != null && bytes.length > 0) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        if (secret.getStringData() != null) {
            return secret.getStringData().get(DATA_KEY);
        }
        return null;
    }

    private Mono<String> createAndReturnKey() {
        String generatedKey = AesGcmCipher.generateKey();
        Secret secret = new Secret();
        Metadata metadata = new Metadata();
        metadata.setName(SECRET_NAME);
        secret.setMetadata(metadata);
        secret.setType(Secret.SECRET_TYPE_OPAQUE);
        secret.setStringData(Map.of(DATA_KEY, generatedKey));
        return client.create(secret).thenReturn(generatedKey);
    }
}