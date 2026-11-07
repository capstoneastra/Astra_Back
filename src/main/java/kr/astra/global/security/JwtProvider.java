package kr.astra.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import kr.astra.user.entity.UserRole;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * JWT 발급·검증 (jjwt 0.13).
 * <ul>
 *   <li>access 토큰: sub=userId, role, orgId. 요청마다 Authorization 헤더로 받음</li>
 *   <li>refresh 토큰: sub=userId, typ=refresh, jti=무작위. 서버는 해시만 refresh_tokens 에 저장</li>
 * </ul>
 * 서명 키는 환경변수 JWT_SECRET (32바이트 이상). 비어 있으면 로컬에서는 임시 키로 동작하고(재시작하면 기존 토큰 무효),
 * prod 프로필에서는 기동을 막습니다.
 */
@Slf4j
@Component
public class JwtProvider {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_ORG_ID = "orgId";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_REFRESH = "refresh";
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long accessValiditySeconds;
    private final long refreshValiditySeconds;

    public JwtProvider(@Value("${astra.jwt.secret:}") String secret,
                       @Value("${astra.jwt.access-token-validity-seconds:3600}") long accessValiditySeconds,
                       @Value("${astra.jwt.refresh-token-validity-seconds:1209600}") long refreshValiditySeconds,
                       Environment environment) {
        this.key = Keys.hmacShaKeyFor(resolveSecret(secret, environment));
        this.accessValiditySeconds = accessValiditySeconds;
        this.refreshValiditySeconds = refreshValiditySeconds;
    }

    private static byte[] resolveSecret(String secret, Environment environment) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= MIN_SECRET_BYTES) {
            return bytes;
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("JWT_SECRET 환경변수에 32바이트 이상 값을 넣어야 합니다.");
        }
        log.warn("JWT_SECRET 이 없거나 32바이트보다 짧아 임시 키를 사용합니다. 서버를 재시작하면 기존 토큰은 무효가 됩니다.");
        byte[] random = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(random);
        return random;
    }

    public long accessValiditySeconds() {
        return accessValiditySeconds;
    }

    public long refreshValiditySeconds() {
        return refreshValiditySeconds;
    }

    public String createAccessToken(Long userId, UserRole role, Long organizationId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(accessValiditySeconds)))
                .signWith(key);
        if (organizationId != null) {
            builder.claim(CLAIM_ORG_ID, organizationId);
        }
        return builder.compact();
    }

    public String createRefreshToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(refreshValiditySeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * access 토큰을 검증해 로그인 사용자로 바꿉니다.
     *
     * @throws ExpiredJwtException 만료 (→ TOKEN_EXPIRED)
     * @throws JwtException        서명 불일치·형식 오류·refresh 토큰을 access 로 사용 (→ AUTH_REQUIRED)
     */
    public CurrentUser parseAccessToken(String token) {
        Claims claims = parse(token);
        if (claims.get(CLAIM_TYPE) != null) {
            throw new JwtException("access 토큰이 아닙니다.");
        }
        Number orgId = claims.get(CLAIM_ORG_ID, Number.class);
        try {
            return new CurrentUser(Long.valueOf(claims.getSubject()),
                    UserRole.valueOf(claims.get(CLAIM_ROLE, String.class)),
                    orgId == null ? null : orgId.longValue());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JwtException("토큰 내용이 올바르지 않습니다.", e);
        }
    }

    /** refresh 토큰의 서명·만료·종류를 확인하고 userId 를 돌려줍니다. 폐기 여부는 DB 에서 따로 확인하세요. */
    public Long parseRefreshToken(String token) {
        Claims claims = parse(token);
        if (!TYPE_REFRESH.equals(claims.get(CLAIM_TYPE))) {
            throw new JwtException("refresh 토큰이 아닙니다.");
        }
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new JwtException("토큰 내용이 올바르지 않습니다.", e);
        }
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
