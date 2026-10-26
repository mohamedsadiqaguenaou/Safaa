// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#ifndef BRAVE_BROWSER_UI_VIEWS_TOOLBAR_NOMUSIC_BUTTON_H_
#define BRAVE_BROWSER_UI_VIEWS_TOOLBAR_NOMUSIC_BUTTON_H_

#include "chrome/browser/ui/views/toolbar/toolbar_button.h"
#include "components/prefs/pref_member.h"
#include "ui/base/metadata/metadata_header_macros.h"

class PrefService;

// Turns NoMusic on or off, like the NoMusic button of the Android toolbar.
// `prefs` are the original profile's: NoMusic is one switch shared by regular
// and private windows (see NoMusicTabHelper).
class NoMusicButton : public ToolbarButton {
  METADATA_HEADER(NoMusicButton, ToolbarButton)
 public:
  explicit NoMusicButton(PrefService* prefs);
  NoMusicButton(const NoMusicButton&) = delete;
  NoMusicButton& operator=(const NoMusicButton&) = delete;
  ~NoMusicButton() override;

 private:
  void ButtonPressed();
  // Shows whether NoMusic is on, also after it was switched elsewhere (another
  // window or Settings).
  void UpdateState();

  BooleanPrefMember enabled_;
};

#endif  // BRAVE_BROWSER_UI_VIEWS_TOOLBAR_NOMUSIC_BUTTON_H_
