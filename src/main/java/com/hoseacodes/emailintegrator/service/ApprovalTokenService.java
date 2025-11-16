package com.hoseacodes.emailintegrator.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ApprovalTokenService {
    
    private static final Logger logger = LoggerFactory.getLogger(ApprovalTokenService.class);
    
    @Value("${app.jwt.secret:mySecretKey}")
    private String jwtSecret;
    
    @Value("${app.jwt.expiration:86400000}") // 24 hours in milliseconds
    private long jwtExpiration;
    
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }
    
    /**
     * Generate approval token for user email
     */
    public String generateApprovalToken(String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", email);
        claims.put("type", "approval");
        
        return createToken(claims, email);
    }
    
    /**
     * Generate approval token with additional user data
     */
    public String generateApprovalToken(String email, String name) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("email", email);
        claims.put("name", name);
        claims.put("type", "approval");
        
        return createToken(claims, email);
    }
    
    /**
     * Create JWT token with claims
     */
    private String createToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);
        
        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }
    
    /**
     * Verify and extract email from approval token
     */
    public String verifyApprovalToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            
            String tokenType = claims.get("type", String.class);
            if (!"approval".equals(tokenType)) {
                logger.warn("Invalid token type: {}", tokenType);
                return null;
            }
            
            return claims.get("email", String.class);
            
        } catch (Exception e) {
            logger.error("Token verification failed: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Verify token and extract all claims
     */
    public Map<String, Object> verifyApprovalTokenWithClaims(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            
            String tokenType = claims.get("type", String.class);
            if (!"approval".equals(tokenType)) {
                logger.warn("Invalid token type: {}", tokenType);
                return null;
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("email", claims.get("email", String.class));
            result.put("name", claims.get("name", String.class));
            result.put("subject", claims.getSubject());
            result.put("issuedAt", claims.getIssuedAt());
            result.put("expiration", claims.getExpiration());
            
            return result;
            
        } catch (Exception e) {
            logger.error("Token verification failed: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if token is expired
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
            
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return true; // Consider invalid tokens as expired
        }
    }
}
