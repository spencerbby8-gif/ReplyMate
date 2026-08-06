package com.replymate.core.listener;

import java.util.List;

/** One parser per messaging app (or reusable across apps). Turns RawNotif into
 *  Either NotifEvents, a safe IGNORE (unsupported/noise), or a FAIL (malformed data).
 *  Parsers never throw for bad input — they FAIL with a reason for diagnostics. */
public interface NotifParser {

    final class Result {
        public enum Kind { EVENTS, IGNORE, FAIL }

        public final Kind kind;
        public final List<NotifEvent> events;    // when EVENTS
        public final String reason;              // when IGNORE/FAIL (short, content-free)

        private Result(Kind kind, List<NotifEvent> events, String reason) {
            this.kind = kind;
            this.events = events;
            this.reason = reason;
        }

        public static Result events(List<NotifEvent> e) { return new Result(Kind.EVENTS, e, null); }
        public static Result ignore(String why) { return new Result(Kind.IGNORE, null, why); }
        public static Result fail(String why) { return new Result(Kind.FAIL, null, why); }
    }

    Result parse(RawNotif raw);
}
