# Paystream API Reference

The Paystream API lets you create payments, issue refunds and receive events about payment status changes. It is organized around REST, accepts JSON request bodies and returns JSON responses. All requests must be made over HTTPS; plain HTTP requests are rejected with a 403 error.

## Authentication

Authenticate by sending your secret API key in the `Authorization` header as a bearer token. Secret keys start with `sk_live_` in production and `sk_test_` in the sandbox. Publishable keys, which start with `pk_`, can only be used from browsers to tokenize card details and cannot create payments.

Keys can be rotated from the dashboard. After rotation, the old key keeps working for 24 hours so you can deploy the new one without downtime. Never embed a secret key in a mobile app or front-end code.

## Rate Limits

Each account may make up to 100 read requests and 50 write requests per second in production. The sandbox allows 25 requests per second in total. When you exceed a limit, the API responds with HTTP 429 and a `Retry-After` header that tells you how many seconds to wait.

We recommend exponential backoff with jitter, starting at 500 milliseconds and capping at 32 seconds. Bulk operations such as exporting all payments should use the reporting endpoints instead of paging through the payments list.

## Idempotency

Network errors can leave you unsure whether a request succeeded. To safely retry a create request, send an `Idempotency-Key` header containing a unique value such as a UUID. If Paystream receives a second request with the same key, it returns the stored result of the first request instead of creating a duplicate payment.

Idempotency keys are stored for 72 hours. Reusing a key with a different request body returns HTTP 422 with the error code `idempotency_key_mismatch`. GET and DELETE requests are idempotent by definition and ignore the header.

## Creating a Payment

Create a payment with `POST /v2/payments`. The required fields are `amount`, `currency` and `payment_method`. Amounts are integers in the smallest currency unit, so 10.50 euros is sent as `1050`. The minimum amount is 50 cents or the equivalent in other currencies; smaller amounts are rejected with the error code `amount_too_small`.

A newly created payment has the status `pending`. Card payments usually move to `succeeded` or `failed` within a few seconds, while bank transfers can stay pending for up to three business days.

## Refunds

Refund a payment with `POST /v2/payments/{id}/refunds`. You can issue a partial refund by passing an `amount`, or omit it to refund the remaining balance. Refunds are possible for up to 180 days after the original payment. Several partial refunds may be issued as long as their total does not exceed the original amount.

Refunded processing fees are not returned to your account. Refunds to cards usually appear on the customer's statement within 5 to 10 business days.

## Webhooks

Webhooks notify your server when something happens, such as `payment.succeeded` or `refund.created`. Register endpoint URLs in the dashboard; each endpoint can subscribe to specific event types.

Every webhook request includes a `Paystream-Signature` header. Verify it by computing an HMAC-SHA256 of the raw request body using your endpoint's signing secret, and reject requests whose timestamp is more than five minutes old to prevent replay attacks.

Your endpoint must return a 2xx status within 10 seconds. Failed deliveries are retried with exponential backoff for up to three days. After three days of failures the endpoint is disabled and the account owner receives an email.

## Pagination

List endpoints return at most 100 objects per page, with a default of 20. Use cursor-based pagination: pass the `next_cursor` value from one response as the `cursor` parameter of the next request. Offset-based pagination is not supported because results can shift while you page through them.

## Errors and Versioning

Errors use conventional HTTP status codes: 400 for invalid parameters, 401 for missing or invalid keys, 404 for unknown objects and 5xx for problems on our side. Every error response includes a machine-readable `code` and a `request_id` that support can use to trace the request.

The API is versioned by date. Pin a version with the `Paystream-Version` header, for example `2026-03-01`. Breaking changes are only released in new versions, and old versions are supported for at least 24 months after a newer version is published.
