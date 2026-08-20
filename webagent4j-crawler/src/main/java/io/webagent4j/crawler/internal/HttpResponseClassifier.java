package io.webagent4j.crawler.internal;

import java.util.Set;

/** Stateless HTTP status code classification. Never retries a status code blindly. */
public final class HttpResponseClassifier {

    private static final Set<Integer> REDIRECT_STATUS_CODES = Set.of(301, 302, 303, 307, 308);

    private HttpResponseClassifier() {
        // utility class
    }

    /** Returns whether {@code statusCode} is a {@code 1xx} informational response. */
    public static boolean isInformational(int statusCode) {
        return statusCode >= 100 && statusCode <= 199;
    }

    /** Returns whether {@code statusCode} is a {@code 2xx} success. */
    public static boolean isSuccess(int statusCode) {
        return statusCode >= 200 && statusCode <= 299;
    }

    /** Returns whether {@code statusCode} is one of the redirect codes this crawler follows. */
    public static boolean isRedirect(int statusCode) {
        return REDIRECT_STATUS_CODES.contains(statusCode);
    }

    /** Returns whether {@code statusCode} is a terminal {@code 4xx} client error. */
    public static boolean isClientError(int statusCode) {
        return statusCode >= 400 && statusCode <= 499;
    }

    /** Returns whether {@code statusCode} is a {@code 5xx} server error. */
    public static boolean isServerError(int statusCode) {
        return statusCode >= 500 && statusCode <= 599;
    }

    /** Returns whether {@code statusCode} is explicitly configured as retryable. */
    public static boolean isRetryable(int statusCode, Set<Integer> retryableStatusCodes) {
        return retryableStatusCodes.contains(statusCode);
    }
}
