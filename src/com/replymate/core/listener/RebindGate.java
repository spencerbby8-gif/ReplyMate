package com.replymate.core.listener;

/** P-anr-startup: PROCESS-LOCAL, I/O-free decision point for
 *  NotificationListenerService.requestRebind attempts.
 *
 *  Why this exists (device-proven, not theoretical): on the AOSP-34 harness
 *  (runs 41386158198 / 41404621261 / 41407382911, stack record
 *  `main state=D ← fdatasync ← libsqlite unixSync/syncJournal ←
 *  SQLiteConnection.setJournalMode`), the FIRST synchronous SQLite open —
 *  journal_mode=WAL commit fsync — ran on the main thread inside the
 *  service-bring-up chain
 *  (Application.onCreate → rebind nudge → RebindPolicy.due → KvDao.get →
 *  SQLiteOpenHelper.onConfigure), producing "executing service
 *  com.replymate.app/.listener.RmNotificationListener" ANRs on three
 *  consecutive runs; the deferred resolution sweep then killed the process,
 *  restarting the listener mid-scenario.
 *
 *  Contract (parity with RebindPolicy, which keeps owning the PERSISTED stamp):
 *    - tryClaim(nowMs): decision + claim are ONE atomic monitor op — claim
 *      BEFORE the framework call, exactly like RebindPolicy.stamp documents
 *      ("so a throwing OEM framework still leaves the bounded stamp").
 *      At most one claim per MIN_INTERVAL_MS, unknown stamp fails OPEN
 *      (recovery is the point), big backward clock skew (>60s) fails open and
 *      re-arms so openness cannot spin either. Small backward jitter (<60s)
 *      stays blocked.
 *    - seed(stampMs): raise-only load of the persisted stamp, called once per
 *      process start from a BACKGROUND thread (the warm path that opens kv,
 *      i.e. pays the WAL fsync OFF the main thread). Never lowers.
 *
 *  Deliberate, disclosed boundary: between process start and the async seed
 *  landing (tens of ms), the gate cannot see the PREVIOUS process's stamp.
 *  Worst case is ONE extra requestRebind per cold process start — the same
 *  safe, idempotent, officially-sanctioned call RebindPolicy already bounds;
 *  within a process (the real flap hazard: disconnect↔connect storms) the
 *  gate is fully synchronous and exact.
 */
public final class RebindGate {

    private long lastClaimMs; // 0 = never claimed (this process knows nothing)

    /** Atomic decide+claim. Thread-safe; callable from ANY thread incl. main
     *  (no I/O of any kind). */
    public synchronized boolean tryClaim(long nowMs) {
        if (lastClaimMs > 0L) {
            long dt = nowMs - lastClaimMs;
            boolean due = dt >= RebindPolicy.MIN_INTERVAL_MS
                || nowMs < lastClaimMs - 60_000L;
            if (!due) return false;
        }
        lastClaimMs = nowMs;
        return true;
    }

    /** Raise the gate's knowledge from the persisted stamp (never lowers). */
    public synchronized void seed(long stampMs) {
        if (stampMs > lastClaimMs) lastClaimMs = stampMs;
    }

    /** Diagnostics/tests: the most recent claim this gate knows about (0 = none). */
    public synchronized long lastClaim() {
        return lastClaimMs;
    }
}
