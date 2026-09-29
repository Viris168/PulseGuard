package com.viris.PulseGuard.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByStripeCustomerId(String stripeCustomerId);

    /**
     * Same lookup, with a row lock ({@code SELECT ... FOR UPDATE}) held until the transaction
     * ends. Stripe sends several events for one subscription at the same instant; the lock makes
     * their syncs for one account run one after another instead of both inserting a row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.stripeCustomerId = :customerId")
    Optional<User> lockByStripeCustomerId(@Param("customerId") String customerId);

    /**
     * Links the Stripe customer, touching only that column. A full save of a user read earlier
     * could write back a stale plan over one a webhook just changed. First link wins.
     *
     * @return 1 if linked, 0 if the user already had a customer
     */
    @Transactional
    @Modifying
    @Query("UPDATE User u SET u.stripeCustomerId = :customerId WHERE u.id = :id AND u.stripeCustomerId IS NULL")
    int linkStripeCustomer(@Param("id") Long id, @Param("customerId") String customerId);

    /**
     * Deletes the row in SQL and leaves the rest to ON DELETE CASCADE. A bulk delete, not
     * {@code delete(entity)}: entities loaded earlier in the transaction (the subscription,
     * the monitors) would otherwise still point at a removed user when Hibernate flushes.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from User u where u.id = :id")
    int deleteByIdCascading(@Param("id") Long id);
}
