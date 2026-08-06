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
import com.replymate.core.ports.SecretVault;
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
import com.replymate.data.dao.UsageDao;
import com.replymate.data.db.DbHelper;
import com.replymate.data.store.SqlContactStore;
import com.replymate.data.store.SqlDraftStore;
import com.replymate.data.store.SqlKvStore;
import com.replymate.data.store.SqlMessageStore;
import com.replymate.data.store.SqlProviderStore;
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
    private final Logger logger;
    private final MessageStore messageStore;
    private final ProfileService profileService;
    private final ProviderStore providerStore;
    private final ScrubLogger scrubLogger;
    private final SecretVault secretVault;
    private final StyleStore styleStore;
    private final UsageStore usageStore;

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
        this.providerStore = new SqlProviderStore(new ProviderDao(dbHelper));
        ProfileService profileService = new ProfileService(sqlKvStore);
        this.profileService = profileService;
        this.contactService = new ContactService(sqlContactStore, systemClock);
        ProviderGateway providerGateway = new ProviderGateway() { // from class: com.replymate.app.di.AppContainer.1
            @Override // com.replymate.core.ports.ProviderGateway
            public AiProvider active() {
                return AppContainer.this.providerOrNull();
            }

            @Override // com.replymate.core.ports.ProviderGateway
            public String activeModel() {
                return AppContainer.this.activeModelOrNull();
            }
        };
        this.gateway = providerGateway;
        this.draftService = new DraftService(sqlContactStore, sqlMessageStore, sqlStyleStore, profileService, sqlDraftStore, sqlUsageStore, providerGateway, uuidGen, systemClock, wrap);
    }

    public AiProvider providerOrNull() {
        String secret;
        try {
            ProviderDef active = this.providerStore.active();
            if (active != null && (secret = this.secretVault.getSecret(active.keyRef)) != null) {
                if (!secret.trim().isEmpty()) {
                    String str = active.baseUrl + "|" + active.modelName + "|" + secret;
                    if (this.cachedProvider == null || !str.equals(this.cachedSignature)) {
                        this.cachedProvider = new GeminiProvider(active.baseUrl, active.modelName, secret, new HttpClient(), new RetryPolicy(), this.logger);
                        this.cachedSignature = str;
                        registerSensitive(secret);
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
}
