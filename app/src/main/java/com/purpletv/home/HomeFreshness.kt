package com.purpletv.home

/** How old Home's rows may get before coming back to it loads them again. */
const val HOME_MAX_AGE_MS = 5 * 60_000L

/** Whether Home loads on coming back: never loaded ([loadedAtMs] 0) or rows [HOME_MAX_AGE_MS] old. */
fun homeIsStale(loadedAtMs: Long, nowMs: Long): Boolean =
    loadedAtMs == 0L || nowMs - loadedAtMs >= HOME_MAX_AGE_MS
