package run.halo.bilicookie.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * SESSDATA 离线解码：Base64 解码后按分隔符扫描出 unix 秒级过期时间戳。
 *
 * <p>社区逆向结论（非官方承诺格式）：SESSDATA 为 URL 编码的 Base64 字符串，
 * 解码后得到分隔符分隔的明文，其中包含一个过期时间戳。启发式识别规则：
 * 该段能解析为整数，且「大于当前时间戳」且「小于当前时间戳 + 1 年」——
 * 正常有效期约 1 个月，用 1 年做上界可过滤随机数、版本号等其他数字字段。</p>
 */
public final class SessdataExpireDecoder {

    private SessdataExpireDecoder() {
    }

    /** 一年秒数，作为过期时间戳上界，过滤干扰字段。 */
    private static final long ONE_YEAR_SECONDS = 366L * 86400;

    /**
     * 从明文 SESSDATA 解码过期时间戳（unix 秒）；无法解码时返回 {@code null}，
     * 由调用方回退到估算或其他在线判定。
     */
    public static Long decodeExpire(String sessdata) {
        if (sessdata == null || sessdata.isBlank()) {
            return null;
        }
        try {
            // 处理 %2C / %3D 等 URL 编码，再还原 base64url 为标准 base64 并补齐
            String s = URLDecoder.decode(sessdata, StandardCharsets.UTF_8)
                .replace('-', '+')
                .replace('_', '/');
            while (s.length() % 4 != 0) {
                s += "=";
            }
            String plain = new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
            long now = Instant.now().getEpochSecond();
            for (String token : plain.split("[./:]")) {
                try {
                    long ts = Long.parseLong(token);
                    if (ts > now && ts < now + ONE_YEAR_SECONDS) {
                        return ts;
                    }
                } catch (NumberFormatException ignored) {
                    // 非数字字段，跳过
                }
            }
        } catch (Exception ignored) {
            // base64/格式异常，返回 null 走回退
        }
        return null;
    }
}
