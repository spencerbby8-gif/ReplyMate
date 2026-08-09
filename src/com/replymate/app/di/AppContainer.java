package com.replymate.app.di;

import android.content.Context;
import com.replymate.app.platform.AndroidLogger;
import com.replymate.app.platform.AndroidSecretVault;
import com.replymate.core.model.ProviderDef;
import com.replymate.core.ports.AiProvider;
import com.replymate.core.ports.ContactStore;
import com.replymate.core.ports.DraftStore;
import com.replymate.core.ports.KvStore;
import com.replymate.core.ports.MessageStore;
import com.replymate.core.ports.ProviderGateway;
import com.replymate.core.ports.ProviderStore;
import com.replymate.core.ports.LearningStore;
import com.replymate.core.ports.SecretVault;
import com.replymate.core.ports.StyleSettingStore;
import com.replymate.core.ports.StyleStore;
import com.replymate.core.ports.UsageStore;
import com.replymate.core.usecase.ContactService;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.core.util.Clock;
import com.replymate.core.util.IdGen;
import com.replymate.core.util.Logger;
import com.replymate.core.util.ScrubLogger;
import com.replymate.core.util.SystemClock;
import com.replymate.core.util.UuidGen;
import com.replymate.data.dao.ContactDao;
import com.replymate.data.dao.DraftDao;
import com.replymate.data.dao.KvDao;
import com.replymate.data.dao.MessageDao;
import com.replymate.data.dao.ProviderDao;
import com.replymate.data.dao.StyleDao;
import com.replymate.data.dao.StyleSettingDao;
import com.replymate.data.dao.StyleSignalDao;
import com.replymate.data.dao.UsageDao;
import com.replymate.data.db.DbHelper;
import com.replymate.data.store.SqlContactStore;
import com.replymate.data.store.SqlDraftStore;
import com.replymate.data.store.SqlKvStore;
import com.replymate.data.store.SqlLearningStore;
import com.replymate.data.store.SqlMessageStore;
import com.replymate.data.store.SqlProviderStore;
import com.replymate.data.store.SqlStyleSettingStore;
import com.replymate.data.store.SqlStyleStore;
import com.replymate.data.store.SqlUsageStore;
import com.replymate.provider.gemini.GeminiProvider;
import com.replymate.provider.http.HttpClient;
import com.replymate.provider.http.RetryPolicy;


public final class AppContainer {
    private AiProvider cachedProvider;
    private String cachedSignature;
    private final Context app;
    private final Clock clock;
    private final ContactService contactService;
    private final ContactStore contactStore;
    private final DbHelper dbHelper;
    private final DraftService draftService;
    private final DraftStore draftStore;
    private final ProviderGateway gateway;
    private final IdGen ids;
    private final KvStore kvStore;
    private final com.replymate.core.learning.LearningService learningService;
    private final LearningStore learningStore;
    private final Logger logger;
    private final com.replymate.core.ports.MemoryStore memoryStore;
    private final com.replymate.core.memory.MemoryService memoryService;
    private final MessageStore messageStore;
    private final ProfileService profileService;
    private final ProviderStore providerStore;
    private final ScrubLogger scrubLogger;
    private final SecretVault secretVault;
    private final StyleSettingStore styleSettingStore;
    private final com.replymate.core.style.StyleService styleService;
    private final StyleStore styleStore;
    private final UsageStore usageStore;
    private com.replymate.core.auth.SessionStore sessionStore;      // lazy (P-auth)
    private com.replymate.core.auth.SupabaseAuth supabaseAuth;      // lazy (P-auth)

    public AppContainer(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.app = applicationContext;
        ScrubLogger wrap = ScrubLogger.wrap(new AndroidLogger());
        this.scrubLogger = wrap;
        this.logger = wrap;
        SystemClock systemClock = new SystemClock();
        this.clock = systemClock;
        UuidGen uuidGen = new UuidGen();
        this.ids = uuidGen;
        DbHelper dbHelper = new DbHelper(applicationContext);
        this.dbHelper = dbHelper;
        SqlKvStore sqlKvStore = new SqlKvStore(new KvDao(dbHelper));
        this.kvStore = sqlKvStore;
        this.secretVault = new AndroidSecretVault(applicationContext);
        SqlContactStore sqlContactStore = new SqlContactStore(new ContactDao(dbHelper));
        this.contactStore = sqlContactStore;
        SqlMessageStore sqlMessageStore = new SqlMessageStore(new MessageDao(dbHelper));
        this.messageStore = sqlMessageStore;
        SqlDraftStore sqlDraftStore = new SqlDraftStore(new DraftDao(dbHelper));
        this.draftStore = sqlDraftStore;
        SqlUsageStore sqlUsageStore = new SqlUsageStore(new UsageDao(dbHelper));
        this.usageStore = sqlUsageStore;
        SqlStyleStore sqlStyleStore = new SqlStyleStore(new StyleDao(dbHelper));
        this.styleStore = sqlStyleStore;
        // P4: customization + learning storage/services (schema v3 tables).
        SqlStyleSettingStore sqlStyleSettingStore =
            new SqlStyleSettingStore(new StyleSettingDao(dbHelper), systemClock);
        this.styleSettingStore = sqlStyleSettingStore;
        SqlLearningStore sqlLearningStore = new SqlLearningStore(new StyleSignalDao(dbHelper));
        this.learningStore = sqlLearningStore;
        // P-memory-audit: long-term memory continuity (facts + rolling summaries,
        // schema v1 tables finally wired; learned-style cache in kv).
        com.replymate.data.store.SqlMemoryStore sqlMemoryStore =
            new com.replymate.data.store.SqlMemoryStore(
                new com.replymate.data.dao.MemoryDao(dbHelper));
        this.memoryStore = sqlMemoryStore;
        com.replymate.core.memory.MemoryService memoryService =
            new com.replymate.core.memory.MemoryService(
                sqlMemoryStore, sqlMessageStore, sqlKvStore, systemClock);
        this.memoryService = memoryService;
        com.replymate.core.learning.LearningService learningService =
            new com.replymate.core.learning.LearningService(
                sqlLearningStore, sqlKvStore, systemClock);
        this.learningService = learningService;
        com.replymate.core.style.StyleService styleService =
            new com.replymate.core.style.StyleService(sqlStyleSettingStore, learningService);
        this.styleService = styleService;
        this.providerStore = new SqlProviderStore(new ProviderDao(dbHelper));
        // P-intelligence-3: solo-builder mode — if this build bundles the owner's
        // built-in key (res injected at release time; EMPTY in the repo), seed a
        // ready provider ONCE on first launch. Pure BYOK builds no-op here. The
        // resource lookup is guarded: missing/stripped stub strings must never be
        // allowed to take container construction down with them.
        String builtinWire = "";
        String builtinKey = "";
        try {
            builtinWire = applicationContext.getString(
                com.replymate.app.R.string.rm_builtin_provider_wire);
            builtinKey = applicationContext.getString(
                com.replymate.app.R.string.rm_builtin_key);
        } catch (RuntimeException ignored) {
            // resource-less environments (unit harnesses) → pure BYOK behavior
        }
        com.replymate.core.privacy.BuiltInSetup.maybeSeed(this.providerStore,
            this.secretVault, builtinWire, builtinKey);
        ProfileService profileService = new ProfileService(sqlKvStore);
        this.profileService = profileService;
        ContactService contactSvc = new ContactService(sqlContactStore, systemClock);
        // P-ux-fix: fork-healer — duplicate contacts for the same chat (different
        // remote keys / manual vs app) merge back into one on sight.
        contactSvc.setMerger(new com.replymate.core.usecase.ContactMerger(
            sqlContactStore, sqlMessageStore, sqlDraftStore, sqlLearningStore,
            sqlStyleSettingStore, sqlMemoryStore, sqlKvStore, systemClock));
        this.contactService = contactSvc;
        ProviderGateway providerGateway = new ProviderGateway() { // from class: com.replymate.app.di.AppContainer.1
            @Override // com.replymate.core.ports.ProviderGateway
            public AiProvider active() {
                return AppContainer.this.providerOrNull();
            }

            @Override // com.replymate.core.ports.ProviderGateway
            public String activeModel() {
                return AppContainer.this.activeModelOrNull();
            }

            @Override // com.replymate.core.ports.ProviderGateway
            public com.replymate.core.model.ProviderRef activeMeta() {
                try {
                    ProviderDef active = AppContainer.this.providerStore.active();
                    return active == null ? null : com.replymate.core.model.ProviderRef.from(active);
                } catch (RuntimeException e) {
                    return null;
                }
            }
        };
        this.gateway = providerGateway;
        this.draftService = new DraftService(sqlContactStore, sqlMessageStore, sqlStyleStore, profileService, sqlDraftStore, sqlUsageStore, providerGateway, uuidGen, systemClock, wrap, styleService, learningService, memoryService);
        // P-intelligence-4: the live-context toggle (Settings → generation context)
        // reads from the same kv; default stays ON when unset.
        this.draftService.setLiveKv(sqlKvStore);
        // P-intelligence-6: the encyclopedia fallback for providers without native
        // search — official free Wikimedia endpoints, keyless, honesty-bounded.
        // P-background-8: the retrieval transport is deliberately NOT the shared
        // 15s/45s provider client — a crawling Wikipedia must never park a draft.
        this.draftService.setRetrieval(
            new com.replymate.provider.retrieval.WikimediaRetrieval(
                com.replymate.provider.retrieval.WikimediaRetrieval.tightHttpClient()));
    }

    public AiProvider providerOrNull() {
        String secret;
        try {
            ProviderDef active = this.providerStore.active();
            if (active != null) {
                secret = active.keyRef.isEmpty()
                    ? "" : this.secretVault.getSecret(active.keyRef);
                // keyless providers (Ollama) don't need a vault secret
                if (secret != null && (active.type.needsKey ? !secret.trim().isEmpty() : true)) {
                    String str = active.type.wire + "|" + active.baseUrl + "|"
                        + active.modelName + "|" + secret;
                    if (this.cachedProvider == null || !str.equals(this.cachedSignature)) {
                        this.cachedProvider = com.replymate.provider.ProviderFactory.build(
                            active, secret, new HttpClient(), new RetryPolicy(), this.logger);
                        this.cachedSignature = str;
                        if (!secret.trim().isEmpty()) registerSensitive(secret);
                    }
                    return this.cachedProvider;
                }
            }
            return null;
        } catch (RuntimeException e) {
            this.logger.e("DI", "provider lookup failed", e);
            return null;
        }
    }

    public String activeModelOrNull() {
        try {
            ProviderDef active = this.providerStore.active();
            if (active == null) {
                return null;
            }
            return active.modelName;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** P-intelligence-3: detected privacy mode + def of the ACTIVE provider for the
     *  UI's privacy surfaces (Home banner, provider list/edit notices). Computed on
     *  demand — never a stale cache. */
    public com.replymate.core.privacy.ProviderPrivacy.Mode privacyMode() {
        return com.replymate.core.privacy.ProviderPrivacy.modeFor(
            this.providerStore.active(), this.kvStore);
    }

    public com.replymate.core.model.ProviderDef activeProviderDef() {
        return this.providerStore.active();
    }

    public void invalidateProvider() {
        this.cachedProvider = null;
        this.cachedSignature = null;
    }

    /** Application context for platform helpers (deep links, package manager). */
    public Context app() {
        return this.app;
    }

    public Logger logger() {
        return this.logger;
    }

    public void registerSensitive(String str) {
        this.scrubLogger.registerSensitive(str);
    }

    public Clock clock() {
        return this.clock;
    }

    public IdGen ids() {
        return this.ids;
    }

    public DbHelper db() {
        return this.dbHelper;
    }

    public KvStore kv() {
        return this.kvStore;
    }

    /** P-auth: local auth session persistence (guest or signed-in). */
    public com.replymate.core.auth.SessionStore sessions() {
        if (sessionStore == null) {
            sessionStore = new com.replymate.core.auth.KvSessionStore(kvStore);
        }
        return sessionStore;
    }

    /** P-auth: Supabase Auth via official REST (no SDK), lazy-built from
     *  auth_config.xml (publishable key is public-by-design). */
    public com.replymate.core.auth.SupabaseAuth auth() {
        if (supabaseAuth == null) {
            supabaseAuth = new com.replymate.core.auth.SupabaseAuth(
                new com.replymate.app.auth.HttpAuthTransport(supabaseBaseUrl(),
                    app.getString(com.replymate.app.R.string.supabase_publishable_key)),
                clock);
        }
        return supabaseAuth;
    }

    public String supabaseBaseUrl() {
        return app.getString(com.replymate.app.R.string.supabase_url);
    }

    public SecretVault vault() {
        return this.secretVault;
    }

    public ContactStore contacts() {
        return this.contactStore;
    }

    public MessageStore messages() {
        return this.messageStore;
    }

    public DraftStore drafts() {
        return this.draftStore;
    }

    public UsageStore usage() {
        return this.usageStore;
    }

    public StyleStore styles() {
        return this.styleStore;
    }

    /** P4: raw style setting rows (global voice + per-contact overrides + custom prompts). */
    public StyleSettingStore styleSettings() {
        return this.styleSettingStore;
    }

    /** P4: learning signal storage. */
    public LearningStore learningStore() {
        return this.learningStore;
    }

    /** P4: voice composition (voice line + extras + audit "why"). */
    public com.replymate.core.style.StyleService styleService() {
        return this.styleService;
    }

    /** P4: learning gate + record/controls (reset, pause, disable, export). */
    public com.replymate.core.learning.LearningService learningService() {
        return this.learningService;
    }

    public ProviderStore providers() {
        return this.providerStore;
    }

    public ProfileService profiles() {
        return this.profileService;
    }

    public ContactService contactService() {
        return this.contactService;
    }

    public DraftService draftService() {
        return this.draftService;
    }

    /** P-memory-audit: long-term memory continuity (facts/summaries/learned style). */
    public com.replymate.core.memory.MemoryService memoryService() {
        return this.memoryService;
    }

    public com.replymate.core.ports.MemoryStore memoryStore() {
        return this.memoryStore;
    }
}
