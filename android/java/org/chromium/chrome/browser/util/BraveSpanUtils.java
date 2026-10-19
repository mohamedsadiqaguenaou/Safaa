/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.util;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;

import org.chromium.build.annotations.NullMarked;

/** Helpers for building formatted text with clickable parts. */
@NullMarked
public class BraveSpanUtils {
    public static Spanned spannedFromHtmlString(String string) {
        return Html.fromHtml(string, Html.FROM_HTML_MODE_LEGACY);
    }

    /**
     * Makes the part of |text| that matches the string |stringId| clickable.
     *
     * @param text The whole text, which |spannableText| was built from.
     * @param spannableText The text to add the span to.
     * @param stringId The string resource whose text is the clickable part.
     * @param clickableSpan The span to apply.
     */
    public static void setSpan(
            Context context,
            String text,
            SpannableString spannableText,
            int stringId,
            ClickableSpan clickableSpan) {
        String spanString = context.getResources().getString(stringId);
        int spanLength = spanString.length();
        int index = text.indexOf(spanString);
        spannableText.setSpan(
                clickableSpan, index, index + spanLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Typeface typeface = Typeface.create("sans-serif", Typeface.NORMAL);
        spannableText.setSpan(
                new StyleSpan(typeface.getStyle()),
                index,
                index + spanLength,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
