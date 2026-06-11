package tech.iamtitan.app.net

/** Wall-clock seconds since the Unix epoch — the `X-Timestamp` source (AG4 anti-replay). */
expect fun nowEpochSeconds(): Long
