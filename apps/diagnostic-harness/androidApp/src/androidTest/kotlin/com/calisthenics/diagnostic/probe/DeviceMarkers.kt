package com.calisthenics.diagnostic.probe

/**
 * `ro.config.marketing_name` is not exposed by the public `Build` API, so it is read via `getprop`.
 *
 * Retail Galaxy S25 Ultra firmware reports this as empty, which is why device authorization keys
 * on the model number and treats marketing name as an additional signal only. Shared between the
 * device gates so a second copy cannot drift.
 */
fun readMarketingName(): String = try {
    Runtime.getRuntime().exec(arrayOf("getprop", "ro.config.marketing_name")).let { process ->
        process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
    }
} catch (_: Exception) {
    ""
}
