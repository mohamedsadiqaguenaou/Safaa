// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUILTIN_LISTS_H_
#define BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUILTIN_LISTS_H_

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

class GURL;

namespace brave_shields {

// A filter list Shields subscribes to by itself when
// BUILDFLAG(BRAVE_SHIELDS_LISTS_FROM_SOURCES) is set: a source of a list that
// Brave's catalog enables by default, fetched from its own site instead of
// through Brave's component updater.
struct BuiltInFilterList {
  std::string_view url;
  // Brave's first-party-protected lists go to the default engine, whose
  // matches don't block first-party requests in standard blocking mode.
  bool default_engine;
  uint8_t permission_mask;
  // Lists Brave maintains may call Brave's own scriptlets.
  bool brave_list;
  // The languages of a regional list, separated by commas; empty for the
  // lists every locale gets.
  std::string_view langs;
};

// Bump when the lists change, so that existing installs subscribe to new ones.
inline constexpr int kBuiltInFilterListsVersion = 1;

// The lists every locale gets, plus the regional lists for `locale`'s
// language.
std::vector<const BuiltInFilterList*> GetBuiltInFilterLists(
    const std::string& locale);

// The built-in list at `url`, or nullptr.
const BuiltInFilterList* FindBuiltInFilterList(const GURL& url);

// Does to a list what Brave's list packager does before shipping it:
// evaluates its `!#if` blocks for Chromium and, unless Brave maintains the
// list, drops its calls to Brave's scriptlets.
std::string PreprocessBuiltInFilterList(std::string_view list, bool brave_list);

}  // namespace brave_shields

#endif  // BRAVE_COMPONENTS_BRAVE_SHIELDS_CORE_BROWSER_AD_BLOCK_BUILTIN_LISTS_H_
