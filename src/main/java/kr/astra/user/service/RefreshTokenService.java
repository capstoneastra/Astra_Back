package kr.astra.user.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import kr.astra.global.security.Hashes;
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
        refreshTokenRepository.save(new RefreshToken(userId, Hashes.sha256(rawToken), expiresAt));
    }

    /**
     * 한 번 쓴 토큰은 바로 폐기합니다 (재발급 시 새 토큰으로 교체).
     *
     * @return 사용 가능했던 토큰의 userId. 없거나 이미 폐기·만료된 토큰이면 empty
     */
    public Optional<Long> consume(String rawToken) {
        OffsetDateTime now = OffsetDateTime.now();
        return refreshTokenRepository.findByTokenHashForUpdate(Hashes.sha256(rawToken))
                .filter(token -> token.isUsable(now))
                .map(token -> {
                    token.revoke(now);
                    return token.getUserId();
                });
    }

    /** 로그아웃: 본인 토큰이면 폐기. 이미 없거나 폐기된 토큰이면 아무것도 하지 않음 */
    public void revoke(Long userId, String rawToken) {
        OffsetDateTime now = OffsetDateTime.now();
        refreshTokenRepository.findByTokenHash(Hashes.sha256(rawToken))
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(token -> token.revoke(now));
    }

    /** 계정 정지 시: 그 사용자의 refresh 토큰 전부 폐기 → 토큰 재발급이 바로 막힘 */
    public void revokeAll(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, OffsetDateTime.now());
    }
}
