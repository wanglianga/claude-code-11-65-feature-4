package com.agri.pit.model;

public enum RunMode {
    /** Original scoring run. Bound to the case cutoff and allowed to produce the frozen snapshot. */
    ORIGINAL,
    /** Re-run at the original cutoff, optionally on a variant boundary version. Trace only. */
    REPLAY,
    /** Controlled backfill after a revision. Uses newly available data but only creates review candidates. */
    CONTROLLED_BACKFILL
}
