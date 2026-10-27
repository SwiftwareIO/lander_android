package com.acme.shop;

import android.app.Activity;
import android.util.Log;

import java.util.Collections;

import link.lander.android.Lander;
import link.lander.android.LanderCallback;
import link.lander.android.LanderLink;
import link.lander.android.LanderLinkOptions;
import link.lander.android.LanderShortLink;
import link.lander.android.LanderSubscription;

/**
 * The SDK from Java. Not used by the app; it is compiled with it so the Java
 * API stays usable.
 */
final class JavaSample {
    private JavaSample() {}

    static LanderSubscription start(Activity activity) {
        Lander.handleIntent(activity.getIntent());
        Lander.getInitialLink(new LanderCallback<LanderLink>() {
            @Override
            public void onSuccess(LanderLink link) {
                if (link != null) Log.d("Acme", "Launch link " + link.getRoute());
            }

            @Override
            public void onError(Exception error) {
                Log.w("Acme", "No launch link", error);
            }
        });
        return Lander.addDeepLinkListener(link -> Log.d("Acme", "Open " + link.getRoute()));
    }

    static void share(String productId) {
        LanderLinkOptions options = new LanderLinkOptions.Builder()
                .path("/product/" + productId)
                .param("productId", productId)
                .channel("share")
                .build();
        Lander.createLink(options, new LanderCallback<LanderShortLink>() {
            @Override
            public void onSuccess(LanderShortLink link) {
                Log.d("Acme", "Share " + link.getUrl());
            }

            @Override
            public void onError(Exception error) {
                Log.w("Acme", "Could not create a link", error);
            }
        });
    }

    static void track(String linkId) {
        Lander.trackEvent("signup");
        Lander.trackEvent("purchase", Collections.singletonMap("amount", 49.99), linkId);
        Log.d("Acme", "Install " + Lander.getInstallId());
    }
}
