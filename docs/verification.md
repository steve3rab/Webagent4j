# Verification

`IVerification` is a deterministic, side-effect-free condition over a minimal
`IVerificationContext`. `Verifier` evaluates conditions in encounter order and preserves a structured
`VerificationResult` for every condition. A mismatch is normal domain data rather than an exception.

V1 exposes URL-fragment verification through the action builder. Element state, title, response,
download, cookie, DOM, and JavaScript conditions belong to later tested increments.
