/* Copyright (c) 2024 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "brave/browser/ui/webui/brave_rewards/rewards_page_ui.h"

#include <utility>

#include "base/check.h"
#include "brave/browser/brave_adaptive_captcha/brave_adaptive_captcha_service_factory.h"
#include "brave/browser/brave_rewards/rewards_service_factory.h"
#include "brave/browser/ui/webui/brave_rewards/rewards_page_data_source.h"
#include "brave/browser/ui/webui/brave_rewards/rewards_page_handler.h"
#include "brave/components/brave_ads/buildflags/buildflags.h"
#include "chrome/browser/profiles/profile.h"

#if BUILDFLAG(ENABLE_BRAVE_ADS)
#include "brave/browser/brave_ads/ads_service_factory.h"
#endif  // BUILDFLAG(ENABLE_BRAVE_ADS)

using brave_adaptive_captcha::BraveAdaptiveCaptchaServiceFactory;

namespace brave_rewards {

namespace {

std::unique_ptr<RewardsPageHandler::BubbleDelegate> MakeBubbleDelegate() {
  return nullptr;
}

}  // namespace

RewardsPageUI::RewardsPageUI(content::WebUI* web_ui, std::string_view host)
    : WebUIController(web_ui) {
  CreateAndAddRewardsPageDataSource(*web_ui, host);
}

RewardsPageUI::~RewardsPageUI() = default;

void RewardsPageUI::BindInterface(
    mojo::PendingReceiver<mojom::RewardsPageHandler> receiver) {
  auto* profile = Profile::FromWebUI(web_ui());
  CHECK(profile);

  handler_ = std::make_unique<RewardsPageHandler>(
      std::move(receiver), MakeBubbleDelegate(),
      RewardsServiceFactory::GetForProfile(profile),
#if BUILDFLAG(ENABLE_BRAVE_ADS)
      brave_ads::AdsServiceFactory::GetForProfile(profile),
#else
      nullptr,
#endif  // BUILDFLAG(ENABLE_BRAVE_ADS)
      BraveAdaptiveCaptchaServiceFactory::GetForProfile(profile),
      profile->GetPrefs());
}

WEB_UI_CONTROLLER_TYPE_IMPL(RewardsPageUI)

}  // namespace brave_rewards
