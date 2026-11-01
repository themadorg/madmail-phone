package org.madmail.mobile.core

/** Saved port preference; privileged routing is separate from engine lifecycle. */
enum class PortMode(val smtp: Int, val imap: Int) {
    Unprivileged(11465, 11993),
    RootStandard(465, 993)
}
