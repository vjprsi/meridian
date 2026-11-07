# Real e-mail delivery via AWS SES — enablement runbook

The platform now supports authenticated STARTTLS/SSL SMTP (SES, SendGrid, any relay). Content is already
branded HTML. Only the sending transport remains. Mailpit (local capture) stays the default; SES is added as a
higher-priority EMAIL provider.

## Why a secret-safe design
The SMTP password is read from an **environment variable** named by the provider's `credentials_ref`
(`SmtpEmailAdapter.resolveSmtpPassword`). It is **never** stored in the provider row or returned by
`GET /v1/notification-providers` (provider config is not redacted on read, so secrets must not live there).

## What YOU do once (AWS side)
SES starts in **sandbox** — it can only send to/from verified identities, so one verification click is required.

1. **Verify the address** (sender = recipient = your Gmail, covers both in sandbox):
   ```
   aws ses verify-email-identity --email-address vjprsi@gmail.com --region us-east-1
   ```
   Click the verification link SES e-mails to vjprsi@gmail.com.
2. **Create SES SMTP credentials**: SES console → *SMTP settings* → *Create SMTP credentials* (this makes a
   scoped IAM user automatically). Note the **SMTP username**, **SMTP password**, and the endpoint
   `email-smtp.<region>.amazonaws.com` (port 587).
   - (To leave the sandbox later and mail anyone: SES console → *Account dashboard* → *Request production access*.)
3. Hand me three values: **region**, **SMTP username**, **SMTP password**.
   *(The role `meridian-ssm-recovery` does not need SES IAM perms for the SMTP path — SMTP creds are their own IAM user.)*

## What I do (no secret ever hits the DB or git)
1. Relaunch notification-service with the password in its env only:
   ```
   SES_SMTP_PASSWORD='<smtp password>'   # set on the process, not committed, not in the DB
   ```
2. Create the EMAIL provider in the demo tenant (secret is only a reference):
   ```
   POST /v1/notification-providers
   { "channel":"EMAIL", "provider_name":"AWS SES", "priority":1, "status":"ACTIVE",
     "sender_id":"vjprsi@gmail.com",
     "config":{ "smtp_host":"email-smtp.<region>.amazonaws.com", "smtp_port":587,
                "smtp_username":"<SMTP username>", "credentials_ref":"SES_SMTP_PASSWORD" } }
   ```
3. Trigger an OTP / payment alert → it lands in your real Gmail as branded HTML. I confirm via the SES send
   result (`ACCEPTED` + message id) and you confirm receipt.

`tools/e2e/configure_ses.sh <region> <smtp_username> <tenant_id>` automates steps 2–3 (reads `SES_SMTP_PASSWORD`
from the env; never takes the secret on the command line).

## SMS (separate, when you want it)
Same pattern with a real SMS adapter (AWS SNS or Twilio) + the Colombian number opted in. Say the word and I'll
add the SNS/Twilio adapter the way SES SMTP was enabled here.
