# BidonSdk.registerAdapter(className) creates the adapter by its class name.
-keep class org.bidon.bidon.BidonAdapter { *; }

# The mediation SDK reads the advertising id through reflection and ships no rules for it, so
# R8 in the publisher's app renames the id client and strips its methods, and every auction
# goes out with a zeroed id. These stand in until the mediation SDK keeps them itself.
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient {
    public static *** getAdvertisingIdInfo(android.content.Context);
}
-keep class com.google.android.gms.ads.identifier.AdvertisingIdClient$Info {
    public *** getId();
    public *** isLimitAdTrackingEnabled();
}
-keep class com.huawei.hms.ads.identifier.AdvertisingIdClient {
    public static *** getAdvertisingIdInfo(android.content.Context);
}
-keep class com.huawei.hms.ads.identifier.AdvertisingIdClient$Info {
    public *** getId();
    public *** isLimitAdTrackingEnabled();
}
