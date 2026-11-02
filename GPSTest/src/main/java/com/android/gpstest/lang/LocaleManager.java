/**
 * Based on https://github.com/YarikSOffice/LanguageTest/blob/master/app/src/main/java/com/yariksoffice/languagetest/LocaleManager.java
 * Licensed under MIT - https://github.com/YarikSOffice/LanguageTest/blob/master/LICENSE
 */

package com.android.gpstest.lang;


import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.preference.PreferenceManager;

import com.android.gpstest.Application;
import com.android.gpstest.R;

import java.util.Locale;

/**
 * Dynamically changes the app locale
 */
public class LocaleManager {

    private final SharedPreferences prefs;

    public LocaleManager(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public Context setLocale(Context c) {
        if (!prefs.contains(c.getString(R.string.pref_key_language))) {
            // User hasn't set the language manually, so use the default context and locale
            return c;
        }
        return updateResources(c, getLanguage(c));
    }

    public Context setNewLocale(Context c, String language) {
        persistLanguage(language);
        return updateResources(c, language);
    }

    String getLanguage(Context c) {
        return prefs.getString(c.getString(R.string.pref_key_language),
                c.getResources().getStringArray(R.array.language_values)[0]); // Default is English
    }

    @SuppressLint("ApplySharedPref")
    private void persistLanguage(String language) {
        // use commit() instead of apply(), because sometimes we kill the application process immediately
        // which will prevent apply() to finish
        prefs.edit().putString(Application.Companion.getApp().getString(R.string.pref_key_language), language).commit();
    }

    private Context updateResources(Context context, String language) {
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        return context.createConfigurationContext(config);
    }

    public static Locale getLocale(Resources res) {
        Configuration config = res.getConfiguration();
        return config.getLocales().get(0);
    }
}
