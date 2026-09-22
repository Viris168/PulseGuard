package com.viris.PulseGuard.billing;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StripeEventRepository extends JpaRepository<StripeEvent, String> {
}
