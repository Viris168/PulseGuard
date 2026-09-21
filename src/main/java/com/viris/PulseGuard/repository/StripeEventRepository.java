package com.viris.PulseGuard.repository;

import com.viris.PulseGuard.model.StripeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StripeEventRepository extends JpaRepository<StripeEvent, String> {
}
