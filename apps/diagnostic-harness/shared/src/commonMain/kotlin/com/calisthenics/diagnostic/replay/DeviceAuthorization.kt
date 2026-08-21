package com.calisthenics.diagnostic.replay

/** The marketing-name substring that identifies the authorized device, when the firmware sets it. */
private const val S25_ULTRA_MARKER = "S25 Ultra"

/**
 * The Galaxy S25 Ultra model-number family: `SM-S938B/U/N/W` across regions.
 *
 * Model number is authoritative, not marketing name. Retail S25 Ultra firmware does not
 * necessarily populate `ro.config.marketing_name` — the device these gates were written for
 * reports it empty — so a marketing-name-only rule rejects the very hardware they authorize.
 */
private const val S25_ULTRA_MODEL_PREFIX = "SM-S938"

/** The one authorized manufacturer, compared case-insensitively. */
private const val AUTHORIZED_MANUFACTURER = "samsung"

/**
 * The single definition of "is this the authorized physical device gate target".
 *
 * Every device gate resolves authorization here. This rule previously existed as four independent
 * copies — the five-run renderer, `tools/check_five_run_replay.py`, the Makefile targets, and the
 * ledger test — and three of them were wrong in the same way, because none had ever run against
 * physical hardware. Adding a gate means calling this, never restating it.
 */
fun isAuthorizedS25Target(
    deviceClass: String,
    manufacturer: String,
    model: String,
    marketingName: String,
): Boolean =
    deviceClass == "physical_device" &&
        manufacturer.lowercase() == AUTHORIZED_MANUFACTURER &&
        (model.uppercase().startsWith(S25_ULTRA_MODEL_PREFIX) || S25_ULTRA_MARKER in marketingName)
