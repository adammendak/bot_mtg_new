package com.adam.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String broker = "capital";
    private boolean executionEnabled = false;
    private String timezone = "Europe/Warsaw";
    private String webhookUrls = "";
    private String webhookSecret = "";
    private String telegramBotToken = "";
    private String telegramChatId = "";
    private String monitorSleepMinutes = "240";
    private final Scan scan = new Scan();
    private final Capital capital = new Capital();
    private final Okx okx = new Okx();
    private String liveAccountName = "bot trading konto";
    private String glowneAccountName = "Glowne";
    private String swingAccountName = "Account H1";
    private String demoAccountName = "Account m15";
    private String htsAccountName = "Account m5";
    /** Capital.com demo sub-account reserved for the isolated MMS book. */
    private String mmsAccountName = "Account MMS";
    private double liveEquityRefuse = 5000;
    private double demoRiskPln = 10;
    private double haltPln = -30;
    private double hardHaltPln = -50;
    private double liveHaltPln = -18;
    private double minDealSize = 0.01;
    /** HTS: cap a position so its broker margin ≤ this fraction of the account's free funds. */
    private double htsMarginBuffer = 0.8;
    /** HTS: cash at risk per new ticket, as a percent of account equity (1.0 = 1%). */
    private double htsRiskPercent = 1.0;
    private int maxOpenNames = 4;
    private String newsCalendarUrl = "https://nfs.faireconomy.media/ff_calendar_thisweek.json";
    private final SddEpics sdd = new SddEpics();
    private final Mms mms = new Mms();

    public String getBroker() {
        return broker;
    }

    public void setBroker(String broker) {
        this.broker = broker;
    }

    public boolean isExecutionEnabled() {
        return executionEnabled;
    }

    public void setExecutionEnabled(boolean executionEnabled) {
        this.executionEnabled = executionEnabled;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getWebhookUrls() {
        return webhookUrls;
    }

    public void setWebhookUrls(String webhookUrls) {
        this.webhookUrls = webhookUrls;
    }

    public List<String> webhookUrlList() {
        if (webhookUrls == null || webhookUrls.isBlank()) {
            return List.of();
        }
        return Arrays.stream(webhookUrls.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public boolean webhookConfigured() {
        return !webhookUrlList().isEmpty();
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String webhookSenderToken() {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return "";
        }
        String trimmed = webhookSecret.trim();
        if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    public String getTelegramBotToken() {
        return telegramBotToken;
    }

    public void setTelegramBotToken(String telegramBotToken) {
        this.telegramBotToken = telegramBotToken;
    }

    public String getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(String telegramChatId) {
        this.telegramChatId = telegramChatId;
    }

    public String getMonitorSleepMinutes() {
        return monitorSleepMinutes;
    }

    public void setMonitorSleepMinutes(String monitorSleepMinutes) {
        this.monitorSleepMinutes = monitorSleepMinutes;
    }

    public boolean telegramConfigured() {
        return telegramBotToken != null && !telegramBotToken.isBlank()
                && telegramChatId != null && !telegramChatId.isBlank();
    }

    public Scan getScan() {
        return scan;
    }

    public Capital getCapital() {
        return capital;
    }

    public Okx getOkx() {
        return okx;
    }

    public String getLiveAccountName() {
        return liveAccountName;
    }

    public void setLiveAccountName(String liveAccountName) {
        this.liveAccountName = liveAccountName;
    }

    public String getGlowneAccountName() {
        return glowneAccountName;
    }

    public void setGlowneAccountName(String glowneAccountName) {
        this.glowneAccountName = glowneAccountName;
    }

    public String getSwingAccountName() {
        return swingAccountName;
    }

    public void setSwingAccountName(String swingAccountName) {
        this.swingAccountName = swingAccountName;
    }

    public String getDemoAccountName() {
        return demoAccountName;
    }

    public void setDemoAccountName(String demoAccountName) {
        this.demoAccountName = demoAccountName;
    }

    public String getHtsAccountName() {
        return htsAccountName;
    }

    public void setHtsAccountName(String htsAccountName) {
        this.htsAccountName = htsAccountName;
    }

    public String getMmsAccountName() {
        return mmsAccountName;
    }

    public void setMmsAccountName(String mmsAccountName) {
        this.mmsAccountName = mmsAccountName;
    }

    public double getLiveEquityRefuse() {
        return liveEquityRefuse;
    }

    public void setLiveEquityRefuse(double liveEquityRefuse) {
        this.liveEquityRefuse = liveEquityRefuse;
    }

    public double getDemoRiskPln() {
        return demoRiskPln;
    }

    public void setDemoRiskPln(double demoRiskPln) {
        this.demoRiskPln = demoRiskPln;
    }

    public double getHaltPln() {
        return haltPln;
    }

    public void setHaltPln(double haltPln) {
        this.haltPln = haltPln;
    }

    public double getHardHaltPln() {
        return hardHaltPln;
    }

    public void setHardHaltPln(double hardHaltPln) {
        this.hardHaltPln = hardHaltPln;
    }

    public double getLiveHaltPln() {
        return liveHaltPln;
    }

    public void setLiveHaltPln(double liveHaltPln) {
        this.liveHaltPln = liveHaltPln;
    }

    public double getMinDealSize() {
        return minDealSize;
    }

    public void setMinDealSize(double minDealSize) {
        this.minDealSize = minDealSize;
    }

    public double getHtsMarginBuffer() {
        return htsMarginBuffer;
    }

    public void setHtsMarginBuffer(double htsMarginBuffer) {
        this.htsMarginBuffer = htsMarginBuffer;
    }

    public double getHtsRiskPercent() {
        return htsRiskPercent;
    }

    public void setHtsRiskPercent(double htsRiskPercent) {
        this.htsRiskPercent = htsRiskPercent;
    }

    public int getMaxOpenNames() {
        return maxOpenNames;
    }

    public void setMaxOpenNames(int maxOpenNames) {
        this.maxOpenNames = maxOpenNames;
    }

    public String getNewsCalendarUrl() {
        return newsCalendarUrl;
    }

    public void setNewsCalendarUrl(String newsCalendarUrl) {
        this.newsCalendarUrl = newsCalendarUrl;
    }

    public SddEpics getSdd() {
        return sdd;
    }

    public Mms getMms() {
        return mms;
    }

    /**
     * MastermindZX MMS mean-reversion tunables ({@link com.adam.server.hts.MmsEngine}).
     * The site re-optimises {@code atrMult} / {@code atrPeriod} / {@code slPct}
     * roughly monthly, so these are env-driven, not constants. {@code symbols}
     * narrows the scan (e.g. a BTC-only forward test) without a code change.
     */
    public static class Mms {
        private int atrPeriod = 20;
        private double atrMult = 2.0;
        private double slPct = 0.02;
        /** {@code TMA_ATR} (default) or {@code BB_ATR}. */
        private String mode = "TMA_ATR";
        /** {@code OPPOSITE_BAND} (site) or {@code FIXED_1R} (MT5 tester clips). */
        private String tpMode = "OPPOSITE_BAND";
        /** {@code PCT} (site) or {@code WICK_EXTREME}. */
        private String slMode = "PCT";
        private boolean addOnEnabled = false;
        private boolean stochFilterEnabled = false;
        private boolean stochCrossEnabled = false;
        /** Closed bars after a band touch that still accept the first reaction. */
        private int reactionWindow = 8;
        /** CSV subset of the MMS universe (BTC,XAU,US100). Blank = all. */
        private String symbols = "";
        /**
         * HTF campaign gate (site: H4 = campaign context, D1 = bias): trade with
         * the higher-TF trend only — LONG only when H4 is bull (HA close bullish
         * OR close &gt; SMA), SHORT only when H4 is bear; conflicting = skip. On
         * by default (the MMS refinement).
         */
        private boolean htfGateEnabled = true;
        private int htfSma = 50;
        /** Also require the D1 campaign to agree (default: H4 only). */
        private boolean htfUseD1 = false;
        /** E-mail each MMS signal. Off for the observe-only forward test (signals still land in hts_signals). */
        private boolean mailEnabled = false;

        public int getAtrPeriod() {
            return atrPeriod;
        }

        public void setAtrPeriod(int atrPeriod) {
            this.atrPeriod = atrPeriod;
        }

        public double getAtrMult() {
            return atrMult;
        }

        public void setAtrMult(double atrMult) {
            this.atrMult = atrMult;
        }

        public double getSlPct() {
            return slPct;
        }

        public void setSlPct(double slPct) {
            this.slPct = slPct;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getTpMode() {
            return tpMode;
        }

        public void setTpMode(String tpMode) {
            this.tpMode = tpMode;
        }

        public String getSlMode() {
            return slMode;
        }

        public void setSlMode(String slMode) {
            this.slMode = slMode;
        }

        public boolean isAddOnEnabled() {
            return addOnEnabled;
        }

        public void setAddOnEnabled(boolean addOnEnabled) {
            this.addOnEnabled = addOnEnabled;
        }

        public boolean isStochFilterEnabled() {
            return stochFilterEnabled;
        }

        public void setStochFilterEnabled(boolean stochFilterEnabled) {
            this.stochFilterEnabled = stochFilterEnabled;
        }

        public boolean isStochCrossEnabled() {
            return stochCrossEnabled;
        }

        public void setStochCrossEnabled(boolean stochCrossEnabled) {
            this.stochCrossEnabled = stochCrossEnabled;
        }

        public int getReactionWindow() {
            return reactionWindow;
        }

        public void setReactionWindow(int reactionWindow) {
            this.reactionWindow = reactionWindow;
        }

        public String getSymbols() {
            return symbols;
        }

        public void setSymbols(String symbols) {
            this.symbols = symbols;
        }

        public boolean isHtfGateEnabled() {
            return htfGateEnabled;
        }

        public void setHtfGateEnabled(boolean htfGateEnabled) {
            this.htfGateEnabled = htfGateEnabled;
        }

        public int getHtfSma() {
            return htfSma;
        }

        public void setHtfSma(int htfSma) {
            this.htfSma = htfSma;
        }

        public boolean isHtfUseD1() {
            return htfUseD1;
        }

        public void setHtfUseD1(boolean htfUseD1) {
            this.htfUseD1 = htfUseD1;
        }

        public boolean isMailEnabled() {
            return mailEnabled;
        }

        public void setMailEnabled(boolean mailEnabled) {
            this.mailEnabled = mailEnabled;
        }

        /** Parsed {@link #symbols} — empty set means "no restriction". */
        public java.util.Set<String> symbolSet() {
            if (symbols == null || symbols.isBlank()) {
                return java.util.Set.of();
            }
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            for (String s : symbols.split(",")) {
                String t = s.trim().toUpperCase();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
            return out;
        }
    }

    public static class Scan {
        private String cron = "0 1,16,31,46 * * * *";
        private String zone = "Europe/Warsaw";

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }
    }

    public static class Capital {
        private final Endpoint demo = new Endpoint();
        private final Endpoint live = new Endpoint();
        private final Endpoint glowne = new Endpoint();
        private final Endpoint swing = new Endpoint();
        private final Endpoint hts = new Endpoint();
        private final Endpoint mms = new Endpoint();

        public Capital() {
            demo.setHost("https://demo-api-capital.backend-capital.com");
            live.setHost("https://api-capital.backend-capital.com");
            glowne.setHost("https://api-capital.backend-capital.com");
            swing.setHost("https://demo-api-capital.backend-capital.com");
            hts.setHost("https://demo-api-capital.backend-capital.com");
            mms.setHost("https://demo-api-capital.backend-capital.com");
        }

        public Endpoint getDemo() {
            return demo;
        }

        public Endpoint getLive() {
            return live;
        }

        public Endpoint getGlowne() {
            return glowne;
        }

        public Endpoint getSwing() {
            return swing;
        }

        public Endpoint getHts() {
            return hts;
        }

        public Endpoint getMms() {
            return mms;
        }
    }

    public static class Endpoint {
        private String apiKey = "";
        private String email = "";
        private String password = "";
        private String host = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public boolean credentialsPresent() {
            return notBlank(apiKey) && notBlank(email) && notBlank(password);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    public static class SddEpics {
        private final Epics epics = new Epics();

        public Epics getEpics() {
            return epics;
        }
    }

    public static class Epics {
        private String ger40 = "DE40";
        private String xau = "GOLD";
        private String us100 = "US100";
        private String eurusd = "EURUSD";
        private String btc = "BTCUSD";
        private String usdjpy = "USDJPY";
        private String us500 = "US500";
        private String xag = "SILVER";
        private String j225 = "J225";

        public String getGer40() {
            return ger40;
        }

        public void setGer40(String ger40) {
            this.ger40 = ger40;
        }

        public String getXau() {
            return xau;
        }

        public void setXau(String xau) {
            this.xau = xau;
        }

        public String getUs100() {
            return us100;
        }

        public void setUs100(String us100) {
            this.us100 = us100;
        }

        public String getEurusd() {
            return eurusd;
        }

        public void setEurusd(String eurusd) {
            this.eurusd = eurusd;
        }

        public String getBtc() {
            return btc;
        }

        public void setBtc(String btc) {
            this.btc = btc;
        }

        public String getUsdjpy() {
            return usdjpy;
        }

        public void setUsdjpy(String usdjpy) {
            this.usdjpy = usdjpy;
        }

        public String getUs500() {
            return us500;
        }

        public void setUs500(String us500) {
            this.us500 = us500;
        }

        public String getXag() {
            return xag;
        }

        public void setXag(String xag) {
            this.xag = xag;
        }

        public String getJ225() {
            return j225;
        }

        public void setJ225(String j225) {
            this.j225 = j225;
        }
    }

    /**
     * OKX (crypto exchange) credentials for the {@code okx} book. Auth is per
     * request: API key + secret + passphrase, signed with HMAC-SHA256. The
     * {@code demo} flag adds {@code x-simulated-trading: 1} so the same key
     * shape works against the demo environment.
     */
    public static class Okx {
        private String apiKey = "";
        private String secret = "";
        private String passphrase = "";
        private String host = "https://www.okx.com";
        private boolean demo = false;
        /** Opt-in required to execute on a REAL-money OKX account (demo=false). */
        private boolean liveExecutionEnabled = false;
        /** HA_OKX: skip a LONG when the last funding rate exceeds this (crowded longs). Fraction per period, +0.05% default. */
        private double htsFundingLongMax = 0.0005;
        /** HA_OKX: skip a SHORT when the last funding rate is below this (crowded shorts). Fraction per period, -0.03% default. */
        private double htsFundingShortMin = -0.0003;
        /** HA_OKX: on a funding-rate API failure, {@code false} = skip the entry (fail closed), {@code true} = allow it. */
        private boolean htsFundingFailOpen = false;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getPassphrase() {
            return passphrase;
        }

        public void setPassphrase(String passphrase) {
            this.passphrase = passphrase;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public boolean isDemo() {
            return demo;
        }

        public void setDemo(boolean demo) {
            this.demo = demo;
        }

        public boolean isLiveExecutionEnabled() {
            return liveExecutionEnabled;
        }

        public void setLiveExecutionEnabled(boolean liveExecutionEnabled) {
            this.liveExecutionEnabled = liveExecutionEnabled;
        }

        public double getHtsFundingLongMax() {
            return htsFundingLongMax;
        }

        public void setHtsFundingLongMax(double htsFundingLongMax) {
            this.htsFundingLongMax = htsFundingLongMax;
        }

        public double getHtsFundingShortMin() {
            return htsFundingShortMin;
        }

        public void setHtsFundingShortMin(double htsFundingShortMin) {
            this.htsFundingShortMin = htsFundingShortMin;
        }

        public boolean isHtsFundingFailOpen() {
            return htsFundingFailOpen;
        }

        public void setHtsFundingFailOpen(boolean htsFundingFailOpen) {
            this.htsFundingFailOpen = htsFundingFailOpen;
        }

        public boolean credentialsPresent() {
            return notBlank(apiKey) && notBlank(secret) && notBlank(passphrase);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
