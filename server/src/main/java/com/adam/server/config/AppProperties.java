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
    private final Scan scan = new Scan();
    private final Capital capital = new Capital();
    private String liveAccountName = "bot trading konto";
    private double liveEquityRefuse = 5000;
    private double demoRiskPln = 10;
    private double haltPln = -30;
    private double hardHaltPln = -50;
    private String newsCalendarUrl = "https://nfs.faireconomy.media/ff_calendar_thisweek.json";
    private final SddEpics sdd = new SddEpics();

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

    public Scan getScan() {
        return scan;
    }

    public Capital getCapital() {
        return capital;
    }

    public String getLiveAccountName() {
        return liveAccountName;
    }

    public void setLiveAccountName(String liveAccountName) {
        this.liveAccountName = liveAccountName;
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

    public String getNewsCalendarUrl() {
        return newsCalendarUrl;
    }

    public void setNewsCalendarUrl(String newsCalendarUrl) {
        this.newsCalendarUrl = newsCalendarUrl;
    }

    public SddEpics getSdd() {
        return sdd;
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

        public Capital() {
            demo.setHost("https://demo-api-capital.backend-capital.com");
            live.setHost("https://api-capital.backend-capital.com");
            glowne.setHost("https://api-capital.backend-capital.com");
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
    }
}
