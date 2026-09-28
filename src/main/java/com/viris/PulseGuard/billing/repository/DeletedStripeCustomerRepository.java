package com.viris.PulseGuard.billing.repository;

import com.viris.PulseGuard.billing.DeletedStripeCustomer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeletedStripeCustomerRepository extends JpaRepository<DeletedStripeCustomer, String> {
}
