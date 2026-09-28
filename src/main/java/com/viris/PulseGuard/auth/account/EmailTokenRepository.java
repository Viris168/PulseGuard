package com.viris.PulseGuard.auth.account;

import com.viris.PulseGuard.enumeration.EmailTokenPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface EmailTokenRepository extends JpaRepository<EmailToken, Long> {

    /** Row-locked, so two requests racing the same link cannot both use it (see the reset tokens). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from EmailToken t join fetch t.user where t.tokenHash = :hash and t.purpose = :purpose")
    Optional<EmailToken> findForUpdate(@Param("hash") String tokenHash, @Param("purpose") EmailTokenPurpose purpose);

    @Modifying
    @Query("delete from EmailToken t where t.user.id = :userId and t.purpose = :purpose")
    int deleteAllForUser(@Param("userId") Long userId, @Param("purpose") EmailTokenPurpose purpose);

    @Modifying
    @Query("delete from EmailToken t where t.user.id = :userId and t.purpose = :purpose and t.email = :email")
    int deleteForAddress(@Param("userId") Long userId, @Param("purpose") EmailTokenPurpose purpose,
                         @Param("email") String email);

    @Modifying
    @Query("delete from EmailToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
