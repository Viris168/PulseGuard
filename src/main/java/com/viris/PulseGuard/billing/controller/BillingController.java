package com.viris.PulseGuard.billing.controller;

import com.viris.PulseGuard.auth.UserPrincipal;
import com.viris.PulseGuard.billing.service.BillingService;
import com.viris.PulseGuard.billing.dto.BillingSummaryResponse;
import com.viris.PulseGuard.billing.dto.CheckoutRequest;
import com.viris.PulseGuard.billing.dto.RedirectResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The billing page's API. Checkout and portal answer with a Stripe URL for the browser to open;
 * the account changes only when Stripe's webhook arrives.
 */
@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
public class BillingController {

    private final BillingService billingService;

    @GetMapping("/subscription")
    public BillingSummaryResponse subscription(@AuthenticationPrincipal UserPrincipal principal) {
        return billingService.summary(principal.getUserId());
    }

    @PostMapping("/checkout")
    public RedirectResponse checkout(@AuthenticationPrincipal UserPrincipal principal,
                                     @Valid @RequestBody CheckoutRequest request) {
        return billingService.startCheckout(principal.getUserId(), request.plan());
    }

    @PostMapping("/portal")
    public RedirectResponse portal(@AuthenticationPrincipal UserPrincipal principal) {
        return billingService.openPortal(principal.getUserId());
    }
}
