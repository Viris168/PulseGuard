---
title: Billing
summary: Paying for Pro or Business, managing your card and invoices, failed payments and cancelling.
describes: billing/SubscriptionSyncService.java, enumeration/SubscriptionStatus.java, billing/service/StripeWebhookService.java, auth/account/AccountDeletionService.java, frontend/src/pages/BillingPage.tsx
---

Paid plans are billed monthly in US dollars through **Stripe**, a payment provider used by
millions of businesses. PulseGuard never sees or stores your card number. For what each plan
includes, see **Plans and limits**.

## Upgrading

On the **Billing** page, choose **Upgrade to Pro** or **Upgrade to Business**, then **Continue to
payment**. You pay on Stripe's secure checkout page and come back to PulseGuard, which shows
"Confirming your payment…" for a moment while Stripe tells us the payment went through. Then the
new plan applies straight away.

If you close the checkout without paying, nothing changes and nothing is charged.

## Card, invoices and receipts

**Billing → Manage billing** opens Stripe's customer portal, where you can:

- change the card you pay with;
- download invoices and receipts;
- switch between Pro and Business (the Billing page's **Switch to …** buttons take you there too);
- cancel.

## If a payment fails

If a monthly payment fails, for example because the card expired, the Billing page shows **Your
last payment failed** with an **Update card** button. You keep your plan while Stripe retries the
payment over the following days. Update your card soon: if the payment still can't be taken,
the subscription ends and your account moves to Free.

## Cancelling

Cancel from **Billing → Manage billing**. You keep your plan until the end of the month you've
already paid for; the Billing page shows it as ending at the period end, with the date. Then your
account moves to Free, and PulseGuard brings it within Free's limits (see **Plans and limits** for
exactly what changes). Nothing is deleted straight away except what Free's shorter history no
longer keeps.

You can change your mind before the end date from the same place.

## Deleting your account

Deleting your account (under **Settings**) cancels your subscription first, so you're never
charged for an account that no longer exists.
