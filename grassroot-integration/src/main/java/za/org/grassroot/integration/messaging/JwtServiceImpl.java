package za.org.grassroot.integration.messaging;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.impl.TextCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import za.org.grassroot.integration.PublicCredentials;
import za.org.grassroot.integration.keyprovider.KeyPairProvider;

import javax.annotation.PostConstruct;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Created by luke on 2017/05/22.
 */
@Service
public class JwtServiceImpl implements JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtServiceImpl.class);

    private String kuid;
    @Value("${grassroot.jwt.token-time-to-live.inMilliSeconds:6000000}")
    private Long jwtTimeToLiveInMilliSeconds;
    @Value("${grassroot.jwt.token-expiry-grace-period.inMilliseconds:1209600000}")
    private Long jwtTokenExpiryGracePeriodInMilliseconds;
    private final KeyPairProvider keyPairProvider;

    @Autowired
    public JwtServiceImpl(KeyPairProvider keyPairProvider) {
        this.keyPairProvider = keyPairProvider;
    }

    @PostConstruct
    public void init() {
        PublicCredentials credentials = refreshPublicCredentials();
        logger.debug("Public credentials generated: {}", credentials);
    }

    @Override
    public PublicCredentials getPublicCredentials() {
        return createCredentialEntity(kuid, keyPairProvider.getJWTKey().getPublic());
    }

    @Override
    public String createJwt(CreateJwtTokenRequest request) {
        Instant now = Instant.now();
        Instant exp = now.plus(convertTypeToExpiryMillis(request.getJwtType()), ChronoUnit.MILLIS);
        request.getHeaderParameters().put("kid", kuid);
        return Jwts.builder()
                .setHeaderParams(request.getHeaderParameters())
                .setClaims(request.getClaims())
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .signWith(
                        SignatureAlgorithm.RS256,
                        keyPairProvider.getJWTKey().getPrivate()
                )
                .compact();
    }

    private long convertTypeToExpiryMillis(JwtType jwtType) {
        switch (jwtType) {
            case ANDROID_CLIENT:
                return Duration.ofDays(7L).toMillis();
            case GRASSROOT_MICROSERVICE:
                return Duration.ofSeconds(1).toMillis();
            default:
                return 1L;
        }
    }

    @Override
    public boolean isJwtTokenValid(String token) {
        try {
            Jwts.parser().setSigningKey(keyPairProvider.getJWTKey().getPublic()).parse(token);
            return true;
        }
        catch (ExpiredJwtException e) {
            logger.error("Token validation failed. The token is expired.", e);
            return false;
        }
        catch (Exception e) {
            logger.error("Unexpected token validation error.", e);
            return false;
        }
    }

    @Override
    public boolean isJwtTokenExpired(String token) {
        try {
            Jwts.parser().setSigningKey(keyPairProvider.getJWTKey().getPublic()).parse(token);
            return false;
        }
        catch (ExpiredJwtException e) {
            logger.error("The token is expired.", e);
            return true;
        }
        catch (Exception e) {
            logger.error("Unexpected token validation error.", e);
            return false;
        }
    }

    @Override
    public String refreshToken(String oldToken, JwtType jwtType) {
        boolean isTokenStillValid = false;
        Date expirationTime = null;
        String newToken = null;
        try {
            Jwts.parser().setSigningKey(keyPairProvider.getJWTKey().getPublic()).parse(oldToken);
            isTokenStillValid = true;
        }
        catch (ExpiredJwtException e) {
            logger.error("Token validation failed. The token is expired.", e);
            expirationTime = e.getClaims().getExpiration();
        }
        if (isTokenStillValid || expirationTime != null
                && expirationTime.toInstant().plus(jwtTokenExpiryGracePeriodInMilliseconds, ChronoUnit.MILLIS).isAfter(new Date().toInstant())) {
            newToken =  createJwt(new CreateJwtTokenRequest(jwtType));
        }

        return newToken;
    }

    private PublicCredentials refreshPublicCredentials() {
        kuid = UUID.randomUUID().toString();
        logger.debug("created KUID for main platform: {}", kuid);
        return createCredentialEntity(kuid, keyPairProvider.getJWTKey().getPublic());
    }

    private PublicCredentials createCredentialEntity(String kuid, PublicKey key) {
        return new PublicCredentials(kuid, TextCodec.BASE64.encode(key.getEncoded()));
    }
}