package com.replymate.core.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** P-intelligence-6: THE GATE. Decides — deterministically, locally, before any
 *  money moves — whether a reply actually needs live web facts, and what for.
 *
 *  Three documented triggers (and nothing else):
 *    MEANING — someone asked what a word/slang/abbreviation means
 *              ("what does odogwu mean", "wym 'no cap'", "wetin be 'kaku'").
 *    CURRENT — the active burst asks for something that CHANGES over time:
 *              scores/results, news, prices, trending topics, "latest/today/
 *              this week" information.
 *    UNKNOWN — a message is CARRIED by a word we can't find in everyday English,
 *              Nigerian Pidgin, or this conversation's own history, and the word
 *              is the point of the message (very short message, quoted, or
 *              repeated) — slang/memes the model may not confidently know.
 *
 *  Ordinary chat must NEVER trigger: everyday vocabulary, names of the people in
 *  the chat, words already used in this thread, and non-question banter all stay
 *  local. Every false positive is money; every trigger is explained by reason. */
public final class SearchGate {

    public enum Kind { NONE, MEANING, CURRENT, UNKNOWN }

    public static final class Need {
        public final Kind kind;
        /** The lookup subject (a term) or query (a current-info question). */
        public final String subject;
        /** Human-readable reason for the audit trail (why this fired). */
        public final String reason;
        private Need(Kind kind, String subject, String reason) {
            this.kind = kind;
            this.subject = subject;
            this.reason = reason;
        }
        public static final Need NONE = new Need(Kind.NONE, "", "");
    }

    private SearchGate() { }

    /** Assess the ACTIVE burst (already scoped by the answered watermark — stale
     *  topics never reach here). history = every message body in the thread that
     *  is NOT part of the active burst (its words count as "already understood in
     *  context"). names = the contact's + owner's names (never subjects). */
    public static Need assess(List<String> activeIncoming, List<String> history,
                              List<String> names) {
        if (activeIncoming == null || activeIncoming.isEmpty()) return Need.NONE;

        // 1. explicit meaning-ask (strongest, exact).
        String asked = detectAsk(activeIncoming, names);
        if (asked != null) {
            return new Need(Kind.MEANING, asked, "they asked what \"" + asked + "\" means");
        }

        // 2. current-information request (score/news/price/trend markers).
        for (int i = activeIncoming.size() - 1; i >= 0; i--) {
            String line = activeIncoming.get(i);
            if (line == null) continue;
            Matcher cur = CURRENT_MARKER.matcher(line);
            if (cur.find()) {
                String q = line.trim();
                if (q.length() > 160) q = q.substring(0, 160) + "…";
                return new Need(Kind.CURRENT, q,
                    "it asks about something that changes (\"" + cur.group(1) + "\")");
            }
        }

        // 3. unknown-word scan — only words that CARRY the message may trigger.
        Set<String> seen = new LinkedHashSet<String>();
        if (history != null) {
            for (String h : history) {
                if (h == null) continue;
                for (String w : words(h)) seen.add(w.toLowerCase(Locale.US));
            }
        }
        // (The burst's own words stay OUT of `seen` on purpose: repetition inside
        // the burst is handled by the occurrences rule, while a word the chat
        // already used in earlier turns is known context, never a lookup.)
        Set<String> lowerNames = new LinkedHashSet<String>();
        if (names != null) {
            for (String n : names) {
                if (n == null) continue;
                for (String w : n.toLowerCase(Locale.US).split("[^\\p{L}]+")) {
                    if (w.length() >= 2) lowerNames.add(w);
                }
            }
        }
        for (int i = activeIncoming.size() - 1; i >= 0; i--) {
            String line = activeIncoming.get(i);
            if (line == null) continue;
            List<String> ws = words(line);           // ORIGINAL case preserved
            if (ws.isEmpty()) continue;
            for (String w : ws) {
                // Quoted tokens arrive with their closing quote still attached
                // ('skibidi' → skibidi') — edge punctuation is not part of a word.
                String token = w.replaceAll("^['-]+", "").replaceAll("['-]+$", "");
                if (token.isEmpty()) continue;
                String low = token.toLowerCase(Locale.US);
                // Proper nouns (Arsenal, Tesla) are never UNKNOWN lookups: the
                // model knows real-world entities; time-sensitive asks about
                // them fire CURRENT above instead. Only words the writer left
                // lowercase can be unknown slang/abbreviations.
                if (!token.equals(low)) continue;
                if (!isUnknownCandidate(low, seen, lowerNames)) continue;
                boolean carried = ws.size() <= 6
                    || isQuoted(line, token)
                    || occurrences(ws, low) >= 2;
                if (carried) {
                    return new Need(Kind.UNKNOWN, low,
                        "\"" + low + "\" isn't everyday English or this chat's"
                            + " vocabulary, and the message leans on it");
                }
            }
        }
        return Need.NONE;
    }

    /* ---------------------------------------------------------- MEANING ---- */

    private static final Pattern ASK_LEAD = Pattern.compile(
        "(?i)\\b(?:what does|what's|whats|what is|what are|wym|wdym|meaning of|means?"
            + "|wetin (?:be|dey mean)|wetin)\\b");
    private static final Pattern ASK_QUOTED = Pattern.compile(
        "(?i)[\"'“]([\\p{L}][\\p{L}'\\- ]{1,30}?)[\"'”]");
    private static final Pattern ASK_WORD = Pattern.compile(
        "(?i)\\b(?:what does|what's|whats|what is|what are|wym|wdym|meaning of|means?"
            + "|wetin (?:be|dey mean))\\s+(?:the word\\s+|the term\\s+)?"
            + "([\\p{L}][\\p{L}'\\-]{1,20})\\b");

    private static String detectAsk(List<String> incoming, List<String> names) {
        for (int i = incoming.size() - 1; i >= 0; i--) {
            String t = incoming.get(i);
            if (t == null || !ASK_LEAD.matcher(t).find()) continue;
            Matcher q = ASK_QUOTED.matcher(t);
            while (q.find()) {
                String cand = clean(q.group(1));
                if (cand != null && !ASK_GENERIC.contains(cand)) return cand;
            }
            Matcher m = ASK_WORD.matcher(t);
            while (m.find()) {
                String cand = clean(m.group(1));
                if (cand != null && !ASK_GENERIC.contains(cand)
                        && !nameHit(cand, names)) return cand;
            }
        }
        return null;
    }

    private static boolean nameHit(String cand, List<String> names) {
        if (names == null) return false;
        for (String n : names) {
            if (n == null) continue;
            for (String w : n.toLowerCase(Locale.US).split("[^\\p{L}]+")) {
                if (w.equals(cand)) return true;
            }
        }
        return false;
    }

    /* ---------------------------------------------------------- CURRENT ---- */

    private static final Pattern CURRENT_MARKER = Pattern.compile(
        "(?i)\\b(latest|trending|right now|today's|tonight's|this (?:week|weekend|month|season)"
            + "|who won|final score|the score|scores?|match result|game result|fixtures?"
            + "|kick ?off|line ?ups?|transfer (?:news|window|deadline)|breaking"
            + "|in the news|the news"
            + "|current (?:price|rate)|price of|how much is|how much does .{1,20} cost"
            + "|exchange rate|fuel price|petrol price|diesel price"
            + "|release date|just (?:dropped|released|came out))\\b");

    /* ---------------------------------------------------------- UNKNOWN ---- */

    static boolean isUnknownCandidate(String w, Set<String> conversationSeen,
                                      Set<String> lowerNames) {
        if (w == null || w.length() < 4 || w.length() > 20) return false;
        if (Character.isDigit(w.charAt(0))) return false;
        // contractions match their plain forms ("you're" → "youre"): chat text is
        // punctuation-rich, the vocabulary is stem-simple.
        String knownForm = w.replace("'", "").replace("-", "");
        if (KNOWN_ENGLISH.contains(w) || KNOWN_ENGLISH.contains(knownForm)
                || KNOWN_PIDGIN_AND_SLANG.contains(w)
                || KNOWN_PIDGIN_AND_SLANG.contains(knownForm)) return false;
        if (conversationSeen.contains(w) || conversationSeen.contains(knownForm)) {
            return false;
        }
        if (lowerNames.contains(w) || lowerNames.contains(knownForm)) return false;
        return true;
    }

    private static boolean isQuoted(String line, String w) {
        return line.contains("\"" + w) || line.contains("'" + w)
            || line.contains("“" + w);
    }

    private static int occurrences(List<String> ws, String lowerW) {
        int n = 0;
        for (String x : ws) if (x.toLowerCase(Locale.US).equals(lowerW)) n++;
        return n;
    }

    /** Tokens in ORIGINAL case (case is signal: lowercase words can be unknown
     *  slang; capitalized words are treated as proper nouns the model knows). */
    static List<String> words(String text) {
        List<String> out = new ArrayList<String>();
        Matcher m = WORD.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}'\\-]{1,}");

    private static String clean(String raw) {
        if (raw == null) return null;
        String v = raw.toLowerCase(Locale.US).trim()
            .replaceAll("\\s+", " ").replaceAll("[^\\p{L}'\\- ]+", "");
        return v.length() >= 2 ? v : null;
    }

    /* --------------------------------------------------- known vocabulary -- */

    /** Chat-filler guard reused from the P5 trigger audit: these are never terms. */
    private static final Set<String> ASK_GENERIC = new LinkedHashSet<String>();
    static {
        Collections.addAll(ASK_GENERIC, new String[] {
            "up","this","that","it","its","wrong","happening","the","matter","going",
            "on","you","happened","mean","means","meant","to","be","being","been",
            "do","does","did","doing","done","so","if","in","at","by","my","mine",
            "your","yours","their","theirs","our","ours","his","her","hers","him",
            "them","us","we","i","me","he","she","they","is","are","was","were",
            "am","a","an","and","or","not","no","yes","dey","una","bro","sis",
            "even","really","actually","now","today","abeg","sha","sef","na","ehn",
            "exactly","please","pls","then","again","first","self","own","thing",
            "one","like","tho","though","tbh","wetin",
            // current-affairs heads: "wetin be the fuel price" is a CURRENT question,
            // not a word-meaning question — these words must not be extracted as
            // MEANING subjects so the gate can fall through to the CURRENT markers.
            "fuel","price","prices","petrol","diesel","score","scores","news",
            "result","results","fixture","fixtures","latest","transfer","rate",
            "rates","election","elections","tribunal"
        });
    }

    /** Everyday English (frequency-pruned): the gate's costs live and die here, so
     *  this set is deliberately BROAD — missing an exotic dictionary word only
     *  means we look it up once and cache it; a false fire costs money. */
    public static final Set<String> KNOWN_ENGLISH = new LinkedHashSet<String>();
    static {
        Collections.addAll(KNOWN_ENGLISH, new String[] {
            "about","above","across","after","again","against","all","almost","alone",
            "along","already","also","although","always","among","another","answer",
            "any","anyone","anything","anyway","around","ask","asked","away","back",
            "bad","because","become","before","began","begin","behind","being","believe",
            "below","best","better","between","big","birthday","bit","black","body",
            "book","both","bought","break","bring","brother","brought","build","business",
            "busy","but","buy","call","called","came","can","cant","cannot","car",
            "care","carry","case","change","check","children","church","city","class",
            "close","cold","come","comes","coming","company","cool","could","couldnt",
            "country","course","day","days","dead","deal","dear","didnt","different",
            "dinner","doesnt","doing","done","dont","door","down","drive","drop",
            "each","early","eat","either","else","end","enough","even","evening",
            "ever","every","everyone","everything","exactly","face","fact","family",
            "far","fast","feel","feeling","fell","felt","few","find","fine","finish",
            "first","food","foot","for","forgot","found","free","friday","friend",
            "friends","from","full","fun","game","gave","get","getting","girl","give",
            "given","glad","goes","going","gone","good","got","great","guy","guys",
            "had","half","hand","happen","happy","hard","has","have","having","head",
            "hear","heard","hello","help","her","here","hey","high","him","his","hold",
            "home","hope","hospital","hot","hour","hours","house","how","however",
            "idea","ill","im","important","inside","into","isnt","issue","its","ive",
            "job","just","keep","kept","kind","knew","know","known","last","late",
            "later","least","leave","left","less","let","lets","life","like","liked",
            "line","list","little","live","lol","long","look","looking","lose","lost",
            "lot","love","loved","lunch","made","make","makes","making","man","many",
            "market","may","maybe","meet","meeting","met","might","mind","mine",
            "miss","missed","mom","moment","monday","money","month","more","morning",
            "most","mother","move","much","music","must","name","near","need","needed",
            "never","new","next","nice","night","nobody","none","nothing","now",
            "number","off","office","okay","old","once","one","only","open","other",
            "others","our","out","outside","over","own","part","party","pass","past",
            "pay","people","person","phone","photo","picture","place","plan","play",
            "please","point","poor","pretty","probably","problem","put","question",
            "quick","quite","rain","read","ready","real","really","reason","remember",
            "reply","rest","right","road","room","run","said","same","saturday",
            "saw","say","saying","school","second","see","seen","send","sent",
            "serious","set","she","shop","short","should","show","side","sir","sister",
            "sit","small","some","someone","something","sometimes","soon","sorry",
            "sound","speak","start","started","stay","still","stop","story","stuff",
            "such","sunday","sure","take","taken","taking","talk","talking","tell",
            "than","thank","thanks","that","thats","their","them","then","there",
            "these","they","thing","think","thinking","this","those","thought","three",
            "through","thursday","time","times","tired","today","together","told",
            "tomorrow","tonight","too","took","town","traffic","true","try","trying",
            "tuesday","turn","two","under","understand","until","use","used","very",
            "video","wait","waiting","walk","want","wanted","war","wasnt","watch",
            "watching","water","way","wednesday","week","weekend","weeks","well",
            "went","were","what","whats","when","where","which","while","white","who",
            "why","will","wish","with","without","woman","wont","word","work","working",
            "world","worried","worry","would","wouldnt","write","wrong","year","years",
            "yesterday","yet","you","young","your","yours","yourself","match","matches",
            "playing","player","players","team","teams","season","league","wedding",
            "flight","flights","ticket","tickets","congrats","congratulations","safe",
            "safety","angry","hungry","sleep","sleeping","slept","awake","morning",
            "afternoon","minute","minutes","seconds","phone","message","messages",
            "voice","call","calls","calling","interview","delivery","delivered",
            "package","address","location","area","street","road","estate","phase",
            "bank","account","transfer","payment","paid","salary","budget","price",
            "prices","expensive","cheap","doctor","drug","drugs","medicine","tablets",
            "fever","headache","pain","sick","treatment","diagnosis",
            "settle","settled","settling","land","landed","landing","garage",
            // internet-common (globally ubiquitous by 2026 — not lookup subjects):
            "selfie","vibes","vibe","goals","dm","dms","link","links","wifi","data",
            "airtime","screenshot","emoji","meme","memes","viral","online","offline",
            "hashtag","streak","spam","group","chat","chats","status","post","posts",
            // everyday emphasis / sport words + common contracted forms (apostrophes
            // are stripped before lookup):
            "actually","basically","literally","definitely","obviously","amazing",
            "goal","youre","theyre","youve","weve","theyve","havent","hasnt",
            "hadnt","couldve","shouldve","wouldve"
        });
    }

    /** Nigerian Pidgin + widely-circulated slang ReplyMate is expected to simply
     *  KNOW (the model does too): they must never cost a lookup. Exotic/new coinages
     *  fall through to the UNKNOWN trigger — one lookup, cached for a week. */
    public static final Set<String> KNOWN_PIDGIN_AND_SLANG = new LinkedHashSet<String>();
    static {
        Collections.addAll(KNOWN_PIDGIN_AND_SLANG, new String[] {
            "wetin","dey","abi","sha","abeg","sef","na","nah","shey","una","wahala",
            "waka","oga","oya","sabi","pikin","jollof","naija","lagos","gbedu",
            "chop","efCC","yansh","kpali","gidi","lasgidi","biko","kai","kmt",
            "howfar","howfa","wazobia","wete","weti","jare","joor","jaree","mafo",
            "sote","hence","anyhoo","aswear","ashewo","area","padi","pally","barb",
            "commot","gallant","ginger","hustle","hustling","shayo","zobo","suya",
            "sapa","vex","don","konji","shege","kpai","gida",
            "akara","moi","moin","egusi","ewa","agidi","asun","kilishi","chin-chin",
            "puff-puff","bole","shoki","azonto","alanta","shaku","gwara","konto",
            // globally-known slang (model knows these cold):
            "sus","bet","cap","yeet","goat","goated","slay","banger","clutch",
            "ghosted","ghosting","simp","simping","flex","flexing","salty","ratio",
            "stan","fomo","wyd","wbu","hbu","nvm","idk","idc","imo","imho","tbh",
            "ngl","fr","frfr","ong","btw","imo","irl","smh","lmao","lmfaoo","brb",
            "gtg","ttyl","dm","ily","asap","fyi","aka","bff","nbd","omg","omw",
            "tlc","atm","rn","hru","sup","wassup","yall","gonna","wanna","gotta",
            "dunno","kinda","sorta","ain't","aint","yup","nope","yeah","yep",
            "ok","okay","kk","haha","hehe","hmm","hm","uh","um","oh","ah","wow",
            "yikes","oops","meh","phew","duh","ew","aww","hbd","gg","wp","af",
            "lowkey","highkey","mid","deadass","troll","trolling","cringe","based",
            "drip","lit","fire","dope","sick","wack","bogus","corny","cheugy",
            "rizz","rizzler","sksk","oop","periodt","finna","tryna","lemme",
            "gimme","wanna","cmon","fam","bestie","boo","bae","bruh","sis","bro"
        });
    }
}
