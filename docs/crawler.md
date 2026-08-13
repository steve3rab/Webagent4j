# Crawler

The crawler module currently establishes composition boundaries toward HTTP and storage. The first
crawler release will implement an HTTP URL queue, normalization, in-memory deduplication, robots.txt,
rate limiting, CSS extraction, structured results, filesystem storage, cancellation, and virtual-thread
concurrency. Nothing in V1 claims that these features are available yet.
