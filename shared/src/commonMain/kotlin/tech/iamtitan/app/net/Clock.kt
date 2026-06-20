package tech.iamtitan.app.net

/** Wall-clock seconds since the Unix epoch — the `X-Timestamp` source ( anti-replay). */
expect fun nowEpochSeconds(): Long
