package com.example.f95updater

/**
 * How a *failed* managed upgrade is worded for a bulk run that has to stop on it.
 *
 * A failure is not a cancellation (see [ManagedUpgradeCancellation]) and it is emphatically not a
 * success: an automatic bulk run must pause on it with the flow's own error, exactly the way an
 * extraction failure already does. Without that, the only thing an automatic run could do with the
 * modal "Upgrade failed" dialog was wait for a user who is not watching — and once that dialog was
 * dismissed the item settled as *Done*, reporting an upgrade that never happened.
 *
 * Deliberately Android-free so the pause reason can be asserted without a device.
 */
object ManagedUpgradeFailure {

    /** The fallback used when a flow hands over a blank error, so the pause is never wordless. */
    const val GENERIC = "The update failed."

    /** The pause reason a bulk run stops on when [itemName]'s upgrade reported [error]. */
    fun bulkMessage(itemName: String?, error: String?): String {
        val item = itemName?.takeIf { it.isNotBlank() }
        val detail = error?.trim()?.ifBlank { null } ?: GENERIC
        return (item?.let { "$it: " } ?: "") + detail +
            " The bulk install was paused; resume it to continue with the next file."
    }
}
