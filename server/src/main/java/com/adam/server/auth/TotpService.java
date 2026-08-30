package com.adam.server.auth;

import com.adam.server.persistence.AppUserEntity;
import com.adam.server.persistence.AppUserRepository;
import com.adam.server.persistence.BackupCodeEntity;
import com.adam.server.persistence.BackupCodeRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * TOTP (RFC 6238) 2FA for admin sign-in (E-7). Enrolment stages a secret in
 * {@code totp_pending_secret}; a verified 6-digit code promotes it to
 * {@code totp_secret} and returns ten one-time backup codes. Verification at
 * login accepts a live TOTP code or an unused backup code.
 */
@Service
public class TotpService {

    private static final Logger log = LoggerFactory.getLogger(TotpService.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final int BACKUP_CODES = 10;

    private final AppUserRepository users;
    private final BackupCodeRepository backupCodes;
    private final DefaultSecretGenerator secretGen = new DefaultSecretGenerator();
    private final DefaultCodeVerifier verifier =
            new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
    private final QrGenerator qrGen = new ZxingPngQrGenerator();
    private final String issuer;

    public TotpService(AppUserRepository users, BackupCodeRepository backupCodes,
                       @Value("${app.auth.totp-issuer:BOT-reinvented}") String issuer) {
        this.users = users;
        this.backupCodes = backupCodes;
        this.issuer = issuer;
        this.verifier.setAllowedTimePeriodDiscrepancy(1); // ±30s clock skew
    }

    /** Stage a fresh secret and return the QR (PNG data URI) + the base32 secret. */
    @Transactional
    public Enrolment startEnrolment(Long userId) {
        AppUserEntity u = users.findById(userId).orElseThrow();
        String secret = secretGen.generate();
        u.setTotpPendingSecret(secret);
        users.save(u);
        QrData data = new QrData.Builder()
                .label(u.getUsername())
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();
        String qr;
        try {
            qr = Utils.getDataUriForImage(qrGen.generate(data), qrGen.getImageMimeType());
        } catch (Exception e) {
            log.warn("QR generation failed: {}", e.getClass().getSimpleName());
            qr = null;
        }
        return new Enrolment(secret, data.getUri(), qr);
    }

    /** Verify the first code against the pending secret; on success enable 2FA + issue backup codes. */
    @Transactional
    public List<String> enable(Long userId, String code) {
        AppUserEntity u = users.findById(userId).orElseThrow();
        String pending = u.getTotpPendingSecret();
        if (pending == null || !verifier.isValidCode(pending, code)) {
            return null;
        }
        u.setTotpSecret(pending);
        u.setTotpPendingSecret(null);
        u.setTotpEnabled(true);
        users.save(u);
        backupCodes.deleteByUserId(userId);
        List<String> plain = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODES; i++) {
            String c = randomCode();
            plain.add(c);
            BackupCodeEntity row = new BackupCodeEntity();
            row.setUserId(userId);
            row.setCodeHash(sha256(c));
            backupCodes.save(row);
        }
        log.info("TOTP enabled for user {} ({} backup codes)", u.getUsername(), BACKUP_CODES);
        return plain;
    }

    /** Turn 2FA off — needs a valid current code or backup code. */
    @Transactional
    public boolean disable(Long userId, String code) {
        AppUserEntity u = users.findById(userId).orElseThrow();
        if (!u.isTotpEnabled() || !verify(userId, code)) {
            return false;
        }
        u.setTotpEnabled(false);
        u.setTotpSecret(null);
        u.setTotpPendingSecret(null);
        users.save(u);
        backupCodes.deleteByUserId(userId);
        log.info("TOTP disabled for user {}", u.getUsername());
        return true;
    }

    /** True if {@code code} is a live TOTP code or an unused backup code (which it then consumes). */
    @Transactional
    public boolean verify(Long userId, String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        AppUserEntity u = users.findById(userId).orElse(null);
        if (u == null || !u.isTotpEnabled() || u.getTotpSecret() == null) {
            return false;
        }
        String trimmed = code.trim().replace(" ", "");
        if (verifier.isValidCode(u.getTotpSecret(), trimmed)) {
            return true;
        }
        String hash = sha256(trimmed.toUpperCase());
        for (BackupCodeEntity b : backupCodes.findByUserIdAndUsedAtIsNull(userId)) {
            if (MessageDigest.isEqual(b.getCodeHash().getBytes(), hash.getBytes())) {
                b.setUsedAt(java.time.Instant.now());
                backupCodes.save(b);
                log.info("Backup code consumed for user id {}", userId);
                return true;
            }
        }
        return false;
    }

    public int unusedBackupCodeCount(Long userId) {
        return backupCodes.findByUserIdAndUsedAtIsNull(userId).size();
    }

    private static String randomCode() {
        // 10 chars, Crockford-ish (no 0/O/1/I), grouped xxxxx-xxxxx
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(alphabet.charAt(RNG.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record Enrolment(String secret, String otpauthUri, String qrDataUri) {
    }
}
