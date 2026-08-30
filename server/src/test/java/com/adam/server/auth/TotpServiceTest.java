package com.adam.server.auth;

import com.adam.server.persistence.AppUserEntity;
import com.adam.server.persistence.AppUserRepository;
import com.adam.server.persistence.BackupCodeEntity;
import com.adam.server.persistence.BackupCodeRepository;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TotpServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final BackupCodeRepository backups = mock(BackupCodeRepository.class);
    private final List<BackupCodeEntity> backupRows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(1);
    private TotpService totp;
    private AppUserEntity user;

    @BeforeEach
    void setUp() {
        user = new AppUserEntity();
        user.setId(7L);
        user.setUsername("adam");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));
        when(backups.save(any(BackupCodeEntity.class))).thenAnswer(i -> {
            BackupCodeEntity e = i.getArgument(0);
            if (e.getId() == null) {
                e.setId(ids.getAndIncrement());
            }
            backupRows.removeIf(r -> r.getId() != null && r.getId().equals(e.getId()));
            backupRows.add(e);
            return e;
        });
        when(backups.findByUserIdAndUsedAtIsNull(7L))
                .thenAnswer(i -> backupRows.stream().filter(r -> r.getUsedAt() == null).toList());
        // no-op delete
        totp = new TotpService(users, backups, "BOT-reinvented");
    }

    private static String codeFor(String secret) throws Exception {
        long t = new SystemTimeProvider().getTime() / 30;
        return new DefaultCodeGenerator().generate(secret, t);
    }

    @Test
    void enrolThenEnableWithAValidCodeReturnsTenBackupCodes() throws Exception {
        TotpService.Enrolment e = totp.startEnrolment(7L);
        assertThat(e.secret()).isNotBlank();
        assertThat(e.otpauthUri()).startsWith("otpauth://totp/");
        assertThat(user.getTotpPendingSecret()).isEqualTo(e.secret());

        List<String> codes = totp.enable(7L, codeFor(e.secret()));
        assertThat(codes).hasSize(10);
        assertThat(user.isTotpEnabled()).isTrue();
        assertThat(user.getTotpSecret()).isEqualTo(e.secret());
        assertThat(user.getTotpPendingSecret()).isNull();
    }

    @Test
    void enableRejectsAWrongCode() {
        totp.startEnrolment(7L);
        assertThat(totp.enable(7L, "000000")).isNull();
        assertThat(user.isTotpEnabled()).isFalse();
    }

    @Test
    void verifyAcceptsALiveCodeAndConsumesABackupCodeOnce() throws Exception {
        TotpService.Enrolment e = totp.startEnrolment(7L);
        List<String> codes = totp.enable(7L, codeFor(e.secret()));

        assertThat(totp.verify(7L, codeFor(e.secret()))).isTrue();

        String backup = codes.getFirst();
        assertThat(totp.verify(7L, backup)).isTrue();   // first use ok
        assertThat(totp.verify(7L, backup)).isFalse();  // already consumed
        assertThat(totp.unusedBackupCodeCount(7L)).isEqualTo(9);
    }

    @Test
    void disableNeedsAValidCode() throws Exception {
        TotpService.Enrolment e = totp.startEnrolment(7L);
        totp.enable(7L, codeFor(e.secret()));

        assertThat(totp.disable(7L, "000000")).isFalse();
        assertThat(totp.disable(7L, codeFor(e.secret()))).isTrue();
        assertThat(user.isTotpEnabled()).isFalse();
        assertThat(user.getTotpSecret()).isNull();
    }
}
