// Copyright (c) 2026 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include "brave/browser/ui/views/toolbar/nomusic_button.h"

#include "base/functional/bind.h"
#include "brave/app/vector_icons/vector_icons.h"
#include "brave/components/constants/pref_names.h"
#include "brave/grit/brave_generated_resources.h"
#include "ui/accessibility/ax_enums.mojom.h"
#include "ui/base/l10n/l10n_util.h"
#include "ui/base/metadata/metadata_impl_macros.h"
#include "ui/views/accessibility/view_accessibility.h"

NoMusicButton::NoMusicButton(PrefService* prefs)
    : ToolbarButton(base::BindRepeating(&NoMusicButton::ButtonPressed,
                                        base::Unretained(this))) {
  enabled_.Init(kNoMusicEnabled, prefs,
                base::BindRepeating(&NoMusicButton::UpdateState,
                                    base::Unretained(this)));
  GetViewAccessibility().SetRole(ax::mojom::Role::kToggleButton);
  UpdateState();
  // Set after the first tooltip, so that the tooltip, which follows the state,
  // doesn't replace it.
  GetViewAccessibility().SetName(
      l10n_util::GetStringUTF16(IDS_ACCNAME_NOMUSIC_BUTTON));
}

NoMusicButton::~NoMusicButton() = default;

void NoMusicButton::ButtonPressed() {
  enabled_.SetValue(!enabled_.GetValue());
}

void NoMusicButton::UpdateState() {
  const bool enabled = enabled_.GetValue();
  SetVectorIcon(enabled ? kNomusicOnIcon : kNomusicOffIcon);
  const int tooltip_id =
      enabled ? IDS_TOOLTIP_NOMUSIC_BUTTON_ON : IDS_TOOLTIP_NOMUSIC_BUTTON_OFF;
  SetTooltipText(l10n_util::GetStringUTF16(tooltip_id));
  GetViewAccessibility().SetCheckedState(enabled
                                             ? ax::mojom::CheckedState::kTrue
                                             : ax::mojom::CheckedState::kFalse);
}

BEGIN_METADATA(NoMusicButton)
END_METADATA
