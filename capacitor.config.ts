import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.ifallhub.app',
  appName: 'ifallhub',
  webDir: 'dist',

  server: {
    // The Capacitor WebView is not same-origin with the Ifallhub engine, so the
    // app talks to a configurable engine URL (Settings dialog) instead of a
    // baked-in proxy. androidScheme keeps cookies/storage in the modern scheme.
    androidScheme: 'https',
    cleartext: true,
  },

  plugins: {
    // Route window.fetch / XMLHttpRequest through the native HTTP stack so API
    // calls to the engine are not blocked by WebView CORS rules.
    CapacitorHttp: {
      enabled: true,
    },
    SplashScreen: {
      launchShowDuration: 1600,
      launchAutoHide: true,
      backgroundColor: '#09090d',
      androidSplashResourceName: 'splash',
      androidScaleType: 'CENTER_CROP',
      showSpinner: false,
    },
    StatusBar: {
      style: 'DARK',
      backgroundColor: '#09090d',
      overlayWebView: false,
    },
  },

  android: {
    allowMixedContent: true,
    buildOptions: {
      keystorePath: process.env.ANDROID_KEYSTORE_PATH,
      keystorePassword: process.env.ANDROID_KEYSTORE_PASSWORD,
      keystoreAlias: process.env.ANDROID_KEYSTORE_ALIAS,
      keystoreAliasPassword: process.env.ANDROID_KEYSTORE_ALIAS_PASSWORD,
    },
  },
};

export default config;
