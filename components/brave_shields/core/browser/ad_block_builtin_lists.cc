// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/components/brave_shields/core/browser/ad_block_builtin_lists.h"

#include <algorithm>
#include <array>
#include <optional>

#include "base/containers/fixed_flat_map.h"
#include "base/strings/string_split.h"
#include "base/strings/string_util.h"
#include "brave/components/brave_shields/core/browser/brave_shields_locale_utils.h"
#include "url/gurl.h"

namespace brave_shields {

namespace {

// From filter_lists/list_catalog.json of github.com/brave/adblock-resources
// (6da9b247): the sources of the lists the catalog enables by default on
// Android, then those of the regional lists, which Brave enables for the
// browser's language. Columns: url, default_engine, permission_mask,
// brave_list, langs.
// clang-format off
constexpr auto kBuiltInFilterLists = std::to_array<BuiltInFilterList>({
    // Brave Default Adblock Filters
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2020.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2021.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2022.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2023.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2024.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2025.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2026.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-general.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/resource-abuse.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/quick-fixes.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/ubo-link-shorteners.txt", true, 1, false, ""},
    {"https://easylist.to/easylist/easylist.txt", true, 1, false, ""},
    {"https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-agh-online.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/brave-unbreak.txt", true, 1, true, ""},
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-specific.txt", true, 1, true, ""},
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-social.txt", true, 1, true, ""},
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-unbreak.txt", true, 1, true, ""},
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-android-specific.txt", true, 1, true, ""},
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-sugarcoat.txt", true, 1, false, ""},
    // Brave Default Privacy Filters
    {"https://easylist.to/easylist/easyprivacy.txt", true, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt", true, 1, false, ""},
    // Brave First Party Adblock Filters
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-firstparty.txt", false, 1, true, ""},
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-firstparty-regional.txt", false, 1, true, ""},
    // Cookie notice blocker
    {"https://secure.fanboy.co.nz/fanboy-cookiemonster_ubo.txt", false, 1, false, ""},
    {"https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances-cookies.txt", false, 1, false, ""},
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/brave-cookie-specific.txt", false, 1, false, ""},
    // Mobile app promo blocker
    {"https://secure.fanboy.co.nz/fanboy-mobile-notifications.txt", false, 1, false, ""},
    // Arabic website ad blocker
    {"https://easylist-downloads.adblockplus.org/Liste_AR.txt", false, 0, false, "ar"},
    // Bulgarian website ad blocker
    {"https://stanev.org/abp/adblock_bg.txt", false, 0, false, "bg"},
    // Chinese website ad blocker
    {"https://filters.adtidy.org/extension/ublock/filters/224.txt", false, 0, false, "zh"},
    // Czech and Slovak website ad blocker
    {"https://raw.githubusercontent.com/tomasko126/easylistczechandslovak/master/filters.txt", false, 0, false, "cs,sk"},
    // Dutch website ad blocker
    {"https://filters.adtidy.org/extension/ublock/filters/8.txt", false, 0, false, "nl"},
    // Estonian website ad blocker
    {"https://ubo-et.lepik.io/list.txt", false, 0, false, "et"},
    // Finnish website ad blocker
    {"https://raw.githubusercontent.com/finnish-easylist-addition/finnish-easylist-addition/gh-pages/Finland_adb.txt", false, 0, false, "fi"},
    // French website ad blocker
    {"https://filters.adtidy.org/extension/ublock/filters/16.txt", true, 0, false, "fr"},
    // German website ad blocker
    {"https://easylist-downloads.adblockplus.org/easylistgermany.txt", true, 0, false, "de"},
    // Greek website ad blocker
    {"https://raw.githubusercontent.com/kargig/greek-adblockplus-filter/master/void-gr-filters.txt", false, 0, false, "el"},
    // Hebrew website ad blocker
    {"https://raw.githubusercontent.com/easylist/EasyListHebrew/master/EasyListHebrew.txt", false, 0, false, "he"},
    {"https://raw.githubusercontent.com/easylist/EasyListHebrew/refs/heads/master/EasyListHebrew-uBO.txt", false, 0, false, "he"},
    // Hindi website ad blocker
    {"https://easylist-downloads.adblockplus.org/indianlist.txt", true, 0, false, "in"},
    // Hungarian website ad blocker
    {"https://cdn.jsdelivr.net/gh/hufilter/hufilter@gh-pages/hufilter-ublock.txt", false, 0, false, "hu"},
    // Icelandic website ad blocker
    {"https://raw.githubusercontent.com/brave/adblock-lists/master/custom/is.txt", false, 0, false, "is"},
    // Indonesian website ad blocker
    {"https://raw.githubusercontent.com/heradhis/indonesianadblockrules/master/subscriptions/abpindo.txt", false, 0, false, "id"},
    // Italian website ad blocker
    {"https://easylist-downloads.adblockplus.org/easylistitaly.txt", true, 0, false, "it"},
    // Japanese website ad blocker
    {"https://filters.adtidy.org/extension/ublock/filters/7.txt", true, 0, false, "ja,jp"},
    // Korean website ad blocker
    {"https://cdn.jsdelivr.net/npm/@filteringdev/filterslists-ko@latest/dist/filterslist-uBlockOrigin-classic.txt", false, 0, false, "ko"},
    // Latvian website ad blocker
    {"https://raw.githubusercontent.com/Latvian-List/adblock-latvian/master/lists/latvian-list.txt", false, 0, false, "lv"},
    // Lithuanian website ad blocker
    {"https://raw.githubusercontent.com/EasyList-Lithuania/easylist_lithuania/master/easylistlithuania.txt", false, 0, false, "lt"},
    // Macedonian website ad blocker
    {"https://raw.githubusercontent.com/DeepSpaceHarbor/Macedonian-adBlock-Filters/master/Filters", false, 0, false, "mk"},
    // Nordic website ad blocker
    {"https://raw.githubusercontent.com/DandelionSprout/adfilt/master/NorwegianList.txt", false, 0, false, "nb,nn,no,da,is"},
    // Persian website ad blocker
    {"https://raw.githubusercontent.com/MasterKia/PersianBlocker/main/PersianBlocker-Deprecated.txt", false, 0, false, "fa"},
    {"https://raw.githubusercontent.com/MasterKia/PersianBlocker/main/PersianBlockerAds.txt", false, 0, false, "fa"},
    {"https://raw.githubusercontent.com/MasterKia/PersianBlocker/main/PersianBlockerTrackers.txt", false, 0, false, "fa"},
    {"https://raw.githubusercontent.com/MasterKia/PersianBlocker/main/PersianBlockerCensor.txt", false, 0, false, "fa"},
    {"https://raw.githubusercontent.com/MasterKia/PersianBlocker/main/PersianBlockerHalfPrice.txt", false, 0, false, "fa"},
    {"https://raw.githubusercontent.com/MasterKia/PersianBlocker/main/PersianBlockerMalware.txt", false, 0, false, "fa"},
    {"https://raw.githubusercontent.com/MasterKia/PersianBlocker/main/PersianBlockerPhishing.txt", false, 0, false, "fa"},
    {"https://raw.githubusercontent.com/MasterKia/PersianBlocker/main/PersianBlockerMobile.txt", false, 0, false, "fa"},
    // Polish website ad blocker
    {"https://raw.githubusercontent.com/MajkiIT/polish-ads-filter/master/polish-adblock-filters/adblock.txt", false, 0, false, "pl"},
    // Romanian website ad blocker
    {"https://raw.githubusercontent.com/tcptomato/ROad-Block/master/road-block-filters-light.txt", false, 0, false, "ro"},
    {"https://raw.githubusercontent.com/tcptomato/ROad-Block/master/road-ubo.txt", false, 0, false, "ro"},
    // Russian website ad blocker - RU AdList
    {"https://easylist-downloads.adblockplus.org/advblock.txt", false, 0, false, "ru,uk,be"},
    {"https://raw.githubusercontent.com/easylist/ruadlist/master/js-fixes-experimental.txt", false, 0, false, "ru,uk,be"},
    // Slovenian website ad blocker
    {"https://raw.githubusercontent.com/betterwebleon/slovenian-list/master/filters.txt", false, 0, false, "sl"},
    // Spanish website ad blocker
    {"https://easylist-downloads.adblockplus.org/easylistspanish.txt", true, 0, false, "es"},
    // Spanish and Portuguese website ad blocker
    {"https://filters.adtidy.org/extension/ublock/filters/9.txt", true, 0, false, "es,pt"},
    // Swedish website ad blocker
    {"https://raw.githubusercontent.com/lassekongo83/Frellwits-filter-lists/master/Frellwits-Swedish-Filter.txt", false, 0, false, "sv"},
    // Thai website ad blocker
    {"https://raw.githubusercontent.com/easylist-thailand/easylist-thailand/master/subscription/easylist-thailand.txt", false, 0, false, "th"},
    // Turkish website ad blocker
    {"https://filters.adtidy.org/extension/ublock/filters/13.txt", false, 0, false, "tr"},
    // Vietnamese website ad blocker
    {"https://raw.githubusercontent.com/abpvn/abpvn/master/filter/abpvn_ublock.txt", false, 0, false, "vi"},
});
// clang-format on

// The `!#if` values Brave's list packager uses for Chromium
// (brave-core-crx-packager, lib/adBlockRustUtils.js). A block on any other
// condition keeps both of its branches, as there.
constexpr auto kIfConditions = base::MakeFixedFlatMap<std::string_view, bool>({
    {"adguard", false},
    {"adguard_app_android", false},
    {"adguard_app_ios", false},
    {"adguard_app_mac", false},
    {"adguard_app_windows", false},
    {"adguard_ext_android_cb", false},
    {"adguard_ext_chromium", true},
    {"adguard_ext_edge", false},
    {"adguard_ext_firefox", false},
    {"adguard_ext_opera", true},
    {"adguard_ext_safari", false},
    {"cap_html_filtering", false},
    {"cap_user_stylesheet", true},
    {"env_chromium", true},
    {"env_devbuild", false},
    {"env_edge", false},
    {"env_firefox", false},
    {"env_legacy", false},
    {"env_safari", false},
    {"ext_abp", false},
    {"ext_devbuild", false},
    {"ext_ublock", true},
    {"false", false},
});

struct IfBlock {
  // Whether the lines around the block are kept.
  bool outer_kept;
  // The condition's value, if it is known.
  std::optional<bool> value;
  bool in_else = false;

  bool Kept() const { return outer_kept && (!value || *value != in_else); }
};

}  // namespace

std::vector<const BuiltInFilterList*> GetBuiltInFilterLists(
    const std::string& locale) {
  const std::string language = GetLanguageCodeFromLocale(locale);
  std::vector<const BuiltInFilterList*> lists;
  for (const auto& list : kBuiltInFilterLists) {
    if (list.langs.empty() ||
        std::ranges::contains(
            base::SplitStringPiece(list.langs, ",", base::TRIM_WHITESPACE,
                                   base::SPLIT_WANT_NONEMPTY),
            language)) {
      lists.push_back(&list);
    }
  }
  return lists;
}

const BuiltInFilterList* FindBuiltInFilterList(const GURL& url) {
  for (const auto& list : kBuiltInFilterLists) {
    if (GURL(list.url) == url) {
      return &list;
    }
  }
  return nullptr;
}

std::string PreprocessBuiltInFilterList(std::string_view list,
                                        bool brave_list) {
  std::string out;
  out.reserve(list.size());
  std::vector<IfBlock> blocks;
  for (std::string_view line : base::SplitStringPiece(
           list, "\n", base::KEEP_WHITESPACE, base::SPLIT_WANT_ALL)) {
    const std::string_view trimmed =
        base::TrimWhitespaceASCII(line, base::TRIM_ALL);
    const bool kept = blocks.empty() || blocks.back().Kept();
    if (trimmed.starts_with("!#if ")) {
      std::string_view condition = trimmed.substr(5);
      const bool negate = condition.starts_with('!');
      if (negate) {
        condition.remove_prefix(1);
      }
      std::optional<bool> value;
      if (const auto it = kIfConditions.find(condition);
          it != kIfConditions.end()) {
        value = it->second != negate;
      }
      // Unlike the packager, an `!#else` inside a dropped block stays dropped.
      blocks.push_back({.outer_kept = kept, .value = value});
      continue;
    }
    if (trimmed == "!#else") {
      if (!blocks.empty()) {
        blocks.back().in_else = !blocks.back().in_else;
      }
      continue;
    }
    if (trimmed == "!#endif") {
      if (!blocks.empty()) {
        blocks.pop_back();
      }
      continue;
    }
    if (!kept || (!brave_list && line.contains("+js(brave-")) ||
        // The packager drops this rule from every list.
        trimmed.starts_with("/^dizipal\\d+\\.com$/##")) {
      continue;
    }
    out.append(line);
    out.push_back('\n');
  }
  return out;
}

}  // namespace brave_shields
