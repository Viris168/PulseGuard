---
title: Your account
summary: Verifying your email, changing your name, email address or password, resetting a forgotten password, and deleting your account.
describes: auth/account/EmailVerificationService.java, frontend/src/components/layout/VerifyEmailBanner.tsx, auth/account/EmailChangeService.java, auth/reset/PasswordResetService.java, auth/AuthService.java, auth/account/AccountDeletionService.java, application.yaml (pulseguard.account-email), frontend/src/pages/settings/ProfileSection.tsx, frontend/src/pages/settings/SecuritySection.tsx
---

Manage your account under **Settings**: your profile, security, alert channels and API keys.

## Verifying your email address

After signing up, open the link in the verification email; it works for 24 hours. Until your
address is verified, **alerts aren't sent to it**, and a banner at the top of the app reminds you.
If the link expired or never arrived, use **Resend email** in that banner, and check your spam
folder.

## Changing your name or email address

Change your name under **Settings → Profile**.

To change the email address you sign in with, use **Change email** in the same place, with your
current password. For your safety it takes two steps:

1. A confirmation link goes to the **new** address. Nothing changes until someone opens it (it
   works for 24 hours).
2. Your **old** address is told about the change, so you'd notice if someone else tried it.

Once confirmed, you sign in with the new address, and your email alert channel moves to it too.

## Changing your password

Change your password under **Settings → Security**, with your current password. Passwords are 8 to
72 characters.

Changing it **signs out your other devices and revokes all your API keys**, in case the old
password was known to someone else. Create new API keys for any tools that need them.

## Forgot your password?

Choose **Forgot password?** on the sign-in page and enter your email address. If it belongs to an
account, you get a reset link that works for **30 minutes**. Setting a new password this way also
signs out every device and revokes your API keys.

## Deleting your account

Under **Settings → Security**, **Delete account** removes everything for good: monitors, checks,
incidents, alert channels, status page, API keys and Ask AI chats. You'll be asked for your
password.

If you're on a paid plan, the subscription is cancelled at the same time, so there are no further
charges. Deleting can't be undone; if you only want to stop paying, cancel your plan instead (see
**Billing**).
