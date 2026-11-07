package kr.astra.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import kr.astra.user.entity.RefreshToken;
import kr.astra.user.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * refresh_tokens 저장·폐기. 토큰 원문은 저장하지 않고 SHA-256 해시(hex 64자)만 저장합니다.
 * 토큰 문자열 생성·서명 검증은 JwtProvider 가 하고, 여기서는 "아직 쓸 수 있는 토큰인지"만 DB 로 판단합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    public void save(Long userId, String rawToken, OffsetDateTime expiresAt) {
        refreshTokenRepository.save(new RefreshToken(userId, hash(rawToken), expiresAt));
    }

    /**
     * 한 번 쓴 토큰은 바로 폐기합니다 (재발급 시 새 토큰으로 교체).
     *
     * @return 사용 가능했던 토큰의 userId. 없거나 이미 폐기·만료된 토큰이면 empty
     */
    public Optional<Long> consume(String rawToken) {
        OffsetDateTime now = OffsetDateTime.now();
        return refreshTokenRepository.findByTokenHash(hash(rawToken))
                .filter(token -> token.isUsable(now))
                .map(token -> {
                    token.revoke(now);
                    return token.getUserId();
                });
    }

    /** 로그아웃: 본인 토큰이면 폐기. 이미 없거나 폐기된 토큰이면 아무것도 하지 않음 */
    public void revoke(Long userId, String rawToken) {
        OffsetDateTime now = OffsetDateTime.now();
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(token -> token.revoke(now));
    }

    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다.", e);
        }
    }
}
