package com.viris.PulseGuard.billing.controller;

import com.viris.PulseGuard.billing.service.StripeWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Stripe's only way in. Unauthenticated by design (SecurityConfig); the signature checked in
 * {@link StripeWebhookService} is what proves the caller is Stripe.
 */
@RestController
public class StripeWebhookController {

    private final StripeWebhookService webhookService;

    public StripeWebhookController(StripeWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * Bytes, not a parsed body: the signature covers the exact payload, so it is decoded here
     * as UTF-8 (what Stripe sends) and never re-serialized.
     */
    @PostMapping("/api/stripe/webhook")
    public ResponseEntity<Void> receive(@RequestBody byte[] payload,
                                        @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        webhookService.handle(new String(payload, StandardCharsets.UTF_8), signature);
        return ResponseEntity.ok().build();
    }
}
