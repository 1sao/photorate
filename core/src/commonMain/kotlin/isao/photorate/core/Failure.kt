package isao.photorate.core

/**
 * Represents expected failures that must be handled in the app (missing permissions, some IO
 * errors, etc.). [Exception]s serve a different purpose: if they are not converted to a [Failure],
 * they should be treated as an unexpected failure, and crash the app.
 */
interface Failure
