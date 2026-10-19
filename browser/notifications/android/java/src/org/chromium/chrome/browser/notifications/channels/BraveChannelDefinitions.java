/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.notifications.channels;

import org.chromium.components.browser_ui.notifications.channels.ChannelDefinitions;
import org.chromium.components.browser_ui.notifications.channels.ChannelDefinitions.PredefinedChannel;

import java.util.Map;
import java.util.Set;

public class BraveChannelDefinitions {
    public static class ChannelId {
        // Brave Ads is not part of this browser. Its ids remain because the patched
        // ChromeChannelDefinitions lists them, and to delete the channels older versions created.
        public static final String BRAVE_ADS = "com.brave.browser.ads";
        public static final String BRAVE_ADS_BACKGROUND = "com.brave.browser.ads.background";
    }

    public static class ChannelGroupId {
        // See ChannelId.BRAVE_ADS.
        public static final String BRAVE_ADS = "com.brave.browser.ads";
        public static final String GENERAL = "general";
    }

    /** Called by ChromeChannelDefinitions. Brave predefines no channels of its own. */
    protected static void addBraveChannels(
            Map<String, PredefinedChannel> map, Set<String> startup) {}

    /** Called by ChromeChannelDefinitions. Brave predefines no channel groups of its own. */
    protected static void addBraveChannelGroups(
            Map<String, ChannelDefinitions.PredefinedChannelGroup> map) {}
}
