package com.replymate.core.plan;

import com.replymate.core.understanding.BurstSignals;
import com.replymate.core.understanding.ConversationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** P-intelligence-5 (directive 2 — REPLY PLANNING): the deterministic planning layer
 *  that runs BEFORE the provider model. It answers, in plain auditable terms:
 *    · what the person is actually saying (topic words + situation),
 *    · what kind of reply this moment needs (answer / follow-up / refusal handling /
 *      correction acceptance / comfort / joke / disagreement / acknowledgment),
 *    · which burst lines matter (focus) and which are consciously ignored (filler),
 *    · what reply length fits the contact and the moment.
 *  The provider then writes ONLY the final text — planning is never delegated to a
 *  "reasoning model", so there is no extra provider call at any depth (Basic =
 *  no plan, Normal = compact plan line, Deep = full plan block — cost/latency
 *  identical by design; multi-call reasoning chains were researched and
 *  deliberately rejected: per-call billing + mobile latency + free-tier RPM).
 *
 *  Every classification rule is conservative and character-verifiable — a wrong
 *  plan must never invent reality, so any doubt falls back to "answer their
 *  message" or "acknowledge and continue", which are always safe.
 *  whyLines feed Prompt Audit verbatim. Pure JVM: pinned hard. */
public final class ReplyPlanner {

    private ReplyPlanner() { }

    /** The reply-intent the planner decided for THIS moment. */
    public enum Intent {
        ANSWER("answer their question first, in the first words of the reply"),
        FOLLOW_UP("react to their news, then ask ONE natural follow-up question"),
        COMFORT("they shared bad news — comfort first, no advice unless asked,"
            + " no jokes unless they joke first"),
        ACCEPT_CORRECTION("accept their correction without sulking, adjust to the"
            + " new version, move on warmly"),
        RESPECT_NO("they said no / can't make it — accept it gracefully, keep the"
            + " door open, never argue the refusal"),
        DISAGREE("they disagreed with you — answer the objection directly and"
            + " honestly; hold your view or concede the real point, never attack"),
        JOKE("they're joking/laughing — match the energy with something light,"
            + " don't turn it into a serious talk"),
        ACKNOWLEDGE("acknowledge what they said and continue the thread naturally"
            + " — a real reaction, not a parrot of their words");

        public final String instruction;
        Intent(String instruction) { this.instruction = instruction; }
    }

    public static final class Plan {
        public Intent intent = Intent.ANSWER;
        public String situation = "";        // what the moment is, in one phrase
        public final List<String> topicWords = new ArrayList<String>();
        public final List<String> focus = new ArrayList<String>();       // quoted burst lines that matter
        public final List<String> ignored = new ArrayList<String>();     // consciously skipped (filler)
        public String lengthPlan = "";        // the length instruction that applies + why
        public final List<String> why = new ArrayList<String>();         // audit lines

        /** Normal depth: one compact line appended to the task. */
        public String compactLine() {
            return "The moment: " + situation + ". Plan: " + intent.instruction
                + (lengthPlan.isEmpty() ? "" : "; " + lengthPlan) + ".";
        }

        /** Deep depth: the full plan block (focus/ignore/topic included). */
        public String fullBlock() {
            StringBuilder t = new StringBuilder(compactLine());
            if (!topicWords.isEmpty()) {
                t.append("\nThis is about: ").append(join(topicWords, " · ")).append('.');
            }
            if (!focus.isEmpty()) {
                t.append("\nThe reply must speak to exactly this: ")
                 .append(quoteList(focus)).append('.');
            }
            if (!ignored.isEmpty()) {
                t.append("\nSkip, filler-only, don't answer these: ")
                 .append(quoteList(ignored)).append('.');
            }
            return t.toString();
        }
    }

    /* ---------------------------------------------------------------- classify */

    private static final String[] COMFORT_WORDS = {
        "sorry for your loss", "passed away", " died", "death", "hospital",
        "hospitalised", "hospitalized", "accident", "lost my job", "got sacked",
        "broke up", "break up", "heartbroken", "i'm devastated", "i am devastated",
        "crying", "not okay", "i'm not fine", "scammed", "robbed", "arrest",
        "sick", "fever", "malaria", "typhoid", "surgery", "grief"
    };
    private static final String[] NEWS_WORDS = {
        "i got the", "got promoted", "i passed", "we won", "guess what", "good news",
        "finally got", "just bought", "booked", "engaged", "she said yes",
        "i'm in", "visa came", "approved my", "new job", "signed my"
    };
    private static final String[] NO_WORDS = {
        "no thanks", "can't make it", "cant make it", "not interested", "count me out",
        "maybe later", "i'm busy", "i am busy", "rain check", "some other time",
        "not today", "i pass", "i'll pass"
    };
    private static final String[] CORRECT_WORDS = {
        "no i said", "that's not what i", "thats not what i", "you're wrong",
        "you are wrong", "i meant", "i told you", "actually,", "actually it's",
        "actually its", "no it's", "no its ", "not like that", "i never said"
    };
    private static final String[] DISAGREE_WORDS = {
        "i disagree", "no way", "that's not true", "thats not true", "i don't think so",
        "i dont think so", "you've got it wrong", "cap", "that's a lie", "thats a lie",
        "not how it happened"
    };
    private static final String[] JOKE_WORDS = {
        "lol", "lmao", "lmfao", "haha", "hehe", "😂", "🤣", "💀", "😭", "jk",
        "just joking", "i'm kidding", "you're so funny", "dey play"
    };
    private static final String[] STOPWORDS = {
        "the", "and", "for", "you", "your", "yours", "that", "this", "with", "have",
        "has", "are", "was", "were", "will", "would", "about", "from", "just",
        "like", "really", "very", "when", "what", "where", "which", "who", "why",
        "how", "did", "does", "doing", "done", "don't", "dont", "can't", "cant",
        "won't", "wont", "isn't", "isnt", "aren't", "arent", "it's", "its", "i'm",
        "im", "i'll", "ill", "i've", "ive", "we're", "they", "them", "their",
        "there", "here", "then", "than", "because", "but", "not", "yes", "yeah",
        "okay", "ok", "please", "thanks", "thank", "bro", "bros", "abeg", "now",
        "today", "tomorrow", "later", "still", "again", "going", "come", "coming",
        "want", "need", "know", "think", "said", "say", "tell", "told", "make",
        "take", "give", "get", "got", "let", "lets", "let's", "see", "seen",
        "good", "nice", "great", "well", "much", "many", "some", "any", "all",
        "our", "out", "over", "under", "same", "more", "most", "less", "least",
        "into", "onto", "upon", "she", "her", "hers", "him", "his", "himself",
        "means", "meaning", "meant", "things", "thing", "something", "anything",
        "everything", "nothing", "one", "two", "first", "even", "only", "also",
        "back", "been", "being", "before", "after", "between", "both", "each",
        "other", "another", "ain't", "aint", "could", "should", "must", "might",
        "may", "shall", "since", "until", "while", "though", "although", "either",
        "neither", "nor", "yet", "already", "always", "never", "ever", "soon",
        "abi", "sha", "sef", "shey", "ode", "lol", "lmao", "haha", "omg", "omw",
        "tbh", "ngl", "idk", "til", "cos", "coz", "cuz", "cause"
    };
    private static final java.util.Set<String> STOP = new java.util.HashSet<String>(
        java.util.Arrays.asList(STOPWORDS));

    /** Build the plan for one generation. All inputs may be sparse; never throws. */
    public static Plan plan(ConversationContext ud, List<String> burstLines,
                            String lengthLabel, String lengthWhy) {
        Plan p = new Plan();
        String newest = ud == null ? "" : ud.newestText.trim();
        String low = " " + newest.toLowerCase(Locale.US)
            .replaceAll("[^\\p{L}\\p{N}'? ]+", " ") + " ";

        // --- intent (priority order: the moment's emotional weight first) ----
        if (containsAny(low, COMFORT_WORDS)) {
            p.intent = Intent.COMFORT;
            p.situation = "they shared something heavy/bad";
        } else if (containsAny(low, CORRECT_WORDS)) {
            p.intent = Intent.ACCEPT_CORRECTION;
            p.situation = "they corrected you or your version of something";
        } else if (containsAny(low, DISAGREE_WORDS)) {
            p.intent = Intent.DISAGREE;
            p.situation = "they pushed back on something you said";
        } else if (containsAny(low, NO_WORDS)) {
            p.intent = Intent.RESPECT_NO;
            p.situation = "they declined or couldn't do the thing on the table";
        } else if (isQuestion(low, ud)) {
            p.intent = Intent.ANSWER;
            p.situation = "they asked you something directly";
        } else if (containsAny(low, NEWS_WORDS)) {
            p.intent = Intent.FOLLOW_UP;
            p.situation = "they shared news/progress about their life";
        } else if (containsAny(low, JOKE_WORDS)) {
            p.intent = Intent.JOKE;
            p.situation = "they're joking around";
        } else {
            p.intent = Intent.ACKNOWLEDGE;
            p.situation = "no direct question — keep the thread warm and moving";
        }
        if (ud != null && ud.burstDetected && ud.signals != null
                && ud.signals.fillerHeavy && p.intent == Intent.ACKNOWLEDGE) {
            p.situation = "they're pinging for attention (filler-heavy burst)";
        }

        // --- what it's about: significant content words from the newest text ----
        topicWords(newest, p.topicWords);

        // --- burst focus / ignore (only verified mechanics: fillers, corrections) -
        BurstSignals.Result sig = ud == null ? null : ud.signals;
        if (burstLines != null && burstLines.size() > 1) {
            for (int i = 0; i < burstLines.size(); i++) {
                String line = burstLines.get(i);
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                boolean filler = BurstSignals.isFiller(trimmed);
                boolean newestOne = i == burstLines.size() - 1;
                // correctionLines are 1-based positions inside the burst
                boolean correction = sig != null
                    && sig.correctionLines.contains(Integer.valueOf(i + 1));
                if (filler && !newestOne) {
                    if (p.ignored.size() < 3) p.ignored.add(clip(trimmed, 48));
                } else if (newestOne || correction || p.focus.isEmpty()) {
                    if (p.focus.size() < 3) p.focus.add(clip(trimmed, 72));
                }
            }
        }
        if (p.focus.isEmpty() && !newest.isEmpty()) p.focus.add(clip(newest, 72));

        // --- length fit ("" = the Off state — the planner stays silent; null =
        //     default; a label = explicit contact/global setting that must win) ---
        if (lengthLabel == null) {
            p.lengthPlan = "length: natural (1–3 short sentences)"
                + adjustForMoment(p.intent, "natural");
        } else if (!lengthLabel.trim().isEmpty()) {
            p.lengthPlan = "length: " + lengthLabel.trim()
                + adjustForMoment(p.intent, lengthLabel)
                + (lengthWhy == null || lengthWhy.trim().isEmpty()
                    ? "" : " (" + lengthWhy.trim() + ")");
        }   // else: Off — no length planning at all

        // --- audit ------------------------------------------------------------
        p.why.add("plan (" + p.intent.name().toLowerCase(Locale.US).replace('_', '-')
            + "): " + p.situation
            + (p.topicWords.isEmpty() ? "" : " · about: " + join(p.topicWords, ", "))
            + (p.focus.size() > 1 ? " · speaking to " + p.focus.size() + " line(s)"
                : "")
            + (p.ignored.isEmpty() ? ""
                : " · skipping " + p.ignored.size() + " filler line(s)"));
        p.why.add(p.lengthPlan.isEmpty()
            ? "plan length: Reply-length control is Off — planner silent on length"
            : "plan length: " + p.lengthPlan);
        return p;
    }

    private static boolean isQuestion(String low, ConversationContext ud) {
        if (low.contains("?")) return true;
        String t = low.trim();
        if (t.matches("^(what|whats|what's|when|where|who|whos|who's|why|how|how's|hows|"
                + "which|can|could|will|would|do|does|did|is|are|am|shall|should|"
                + "wanna|you down|you good|you there|you dey)\\b.*")) return true;
        return ud != null && ud.signals != null && ud.signals.questions > 0
            && !ud.signals.fillerHeavy;
    }

    /** Moment-aware nudge onto the contact's configured length. */
    private static String adjustForMoment(Intent intent, String label) {
        switch (intent) {
            case COMFORT:
                return label.equals("short")
                    ? " — but don't be abrupt here; warmth takes one extra line"
                    : " — warmth over brevity tricks";
            case JOKE:
                return " — keep it punchy, jokes die in paragraphs";
            case ANSWER:
                return label.equals("detailed")
                    ? " — full detail only if the question asks for it" : "";
            default:
                return "";
        }
    }

    /** Up to three significant words (longest, stopword-free) as the topic. */
    static void topicWords(String text, List<String> out) {
        if (text == null) return;
        String[] toks = text.toLowerCase(Locale.US)
            .replaceAll("[^\\p{L}' ]+", " ").trim().split("\\s+");
        java.util.Set<String> seen = new java.util.LinkedHashSet<String>();
        for (String t : toks) {
            if (t.length() >= 4 && !STOP.contains(t) && !t.startsWith("'")) seen.add(t);
        }
        List<String> ranked = new ArrayList<String>(seen);
        java.util.Collections.sort(ranked, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                return b.length() - a.length();
            }
        });
        for (int i = 0; i < ranked.size() && out.size() < 3; i++) out.add(ranked.get(i));
    }

    private static boolean containsAny(String hay, String[] needles) {
        for (String n : needles) if (hay.contains(n)) return true;
        return false;
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String quoteList(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append("; ");
            sb.append('"').append(xs.get(i)).append('"');
        }
        return sb.toString();
    }

    private static String join(List<String> xs, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(xs.get(i));
        }
        return sb.toString();
    }
}
