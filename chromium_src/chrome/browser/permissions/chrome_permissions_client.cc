/* Copyright (c) 2021 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "chrome/browser/permissions/chrome_permissions_client.h"

#include "brave/components/brave_wallet/common/buildflags/buildflags.h"
#include "url/origin.h"

#include <chrome/browser/permissions/chrome_permissions_client.cc>

bool ChromePermissionsClient::BraveCanBypassEmbeddingOriginCheck(
    ContentSettingsType type,
    const GURL& requesting_origin,
    const GURL& embedding_origin) {
  // Note that requesting_origin has an address in it at this point.
  // But even if we get the original origin without the address, we can't
  // check it against the embedding origin for BRAVE_ETHEREUM and BRAVE_SOLANA
  // here because it can be allowed across origins via the iframe `allow`
  // attribute with the `ethereum` and `solana` feature policy.
  // Without this check we'd fail Chromium's origin check.
  // We instead handle this in brave_wallet_render_frame_observer.cc by not
  // exposing the API which can request permission when the origin is 3p and
  // the feature policy is not allowed explicitly. We ensure that the correct
  // handling is covered via the browser tests:
  // SolanaProviderRendererTest.Iframe3P and
  // JSEthereumProviderBrowserTest.Iframe3P
#if BUILDFLAG(ENABLE_BRAVE_WALLET)
  if (type == ContentSettingsType::BRAVE_ETHEREUM ||
      type == ContentSettingsType::BRAVE_SOLANA ||
      type == ContentSettingsType::BRAVE_CARDANO ||
      type == ContentSettingsType::BRAVE_POLKADOT) {
    return true;
  }
#endif

  return CanBypassEmbeddingOriginCheck(requesting_origin, embedding_origin);
}
