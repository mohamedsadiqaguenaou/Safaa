/* Copyright (c) 2022 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "chrome/browser/net/stub_resolver_config_reader.h"
#include "base/feature_list.h"
#include "brave/components/brave_vpn/common/buildflags/buildflags.h"
#include "build/buildflag.h"
#include "chrome/browser/net/default_dns_over_https_config_source.h"
#include "chrome/browser/net/secure_dns_config.h"
#include "chrome/common/pref_names.h"
#include "components/prefs/pref_service.h"
#include "content/public/browser/network_service_instance.h"
#include "net/dns/public/dns_over_https_config.h"
#include "net/dns/public/secure_dns_mode.h"
#include "services/network/public/mojom/network_service.mojom.h"

#if BUILDFLAG(ENABLE_BRAVE_VPN)
#include "brave/components/brave_vpn/common/features.h"
#endif

#if BUILDFLAG(IS_WIN) && BUILDFLAG(ENABLE_BRAVE_VPN)
namespace {

bool IsBraveVPNConnected(PrefService* local_state) {
  if (!base::FeatureList::IsEnabled(
          brave_vpn::features::kBraveVPNDnsProtection)) {
    return false;
  }
  return !local_state->GetString(prefs::kBraveVpnDnsConfig).empty();
}

bool ShouldOverride(net::SecureDnsMode secure_dns_mode,
                    PrefService* local_state,
                    SecureDnsConfig::ManagementMode management_mode,
                    bool is_managed) {
  if (!IsBraveVPNConnected(local_state))
    return false;
  if (secure_dns_mode == net::SecureDnsMode::kSecure)
    return false;
  if (management_mode != SecureDnsConfig::ManagementMode::kNoOverride ||
      is_managed) {
    // there is already a managed policy or parental control in place
    return false;
  }

  return true;
}

net::InsecureDnsMode MaybeOverrideDnsClientEnabled(
    net::SecureDnsMode secure_dns_mode,
    net::InsecureDnsMode insecure_dns_mode,
    PrefService* local_state,
    SecureDnsConfig::ManagementMode management_mode,
    bool is_managed) {
  if (ShouldOverride(secure_dns_mode, local_state, management_mode,
                     is_managed)) {
    // disable the insecure client for doh
    return net::InsecureDnsMode::kDisabled;
  }

  return insecure_dns_mode;
}

net::SecureDnsMode MaybeOverrideDnsMode(
    net::SecureDnsMode secure_dns_mode,
    PrefService* local_state,
    SecureDnsConfig::ManagementMode management_mode,
    bool is_managed) {
  if (ShouldOverride(secure_dns_mode, local_state, management_mode,
                     is_managed)) {
    return net::SecureDnsMode::kSecure;
  }
  return secure_dns_mode;
}

net::DnsOverHttpsConfig MaybeOverrideDnsConfig(
    net::SecureDnsMode secure_dns_mode,
    net::DnsOverHttpsConfig doh_config,
    PrefService* local_state,
    SecureDnsConfig::ManagementMode management_mode,
    bool is_managed) {
  if (ShouldOverride(secure_dns_mode, local_state, management_mode,
                     is_managed)) {
    return net::DnsOverHttpsConfig::FromStringLax(
        local_state->GetString(prefs::kBraveVpnDnsConfig));
  }
  return doh_config;
}

SecureDnsConfig::ManagementMode MaybeOverrideForcedManagementMode(
    net::SecureDnsMode secure_dns_mode,
    PrefService* local_state,
    SecureDnsConfig::ManagementMode management_mode,
    bool is_managed) {
  // Don't change management mode if the doh settings are
  // managed by policy or by parental controls
  if (is_managed ||
      management_mode != SecureDnsConfig::ManagementMode::kNoOverride)
    return management_mode;

  // Otherwise always block changes to the doh config while the VPN
  // is connected
  if (IsBraveVPNConnected(local_state))
    return SecureDnsConfig::ManagementMode::kDisabledManaged;

  return management_mode;
}

std::vector<net::IPEndPoint> MaybeOverrideFallbackDohNameservers(
    net::SecureDnsMode secure_dns_mode,
    PrefService* local_state,
    SecureDnsConfig::ManagementMode management_mode,
    bool is_managed,
    std::vector<net::IPEndPoint> fallback_doh_nameservers) {
  if (ShouldOverride(secure_dns_mode, local_state, management_mode,
                     is_managed)) {
    return std::vector<net::IPEndPoint>();
  }

  return fallback_doh_nameservers;
}

}  // namespace

#define SecureDnsConfig(SECURE_DNS_MODE, SECURE_DOH_CONFIG,                    \
                        FORCED_MANAGEMENT_MODE, FALLBACK_DOH_NAMESERVERS)      \
  SecureDnsConfig(                                                             \
      MaybeOverrideDnsMode(SECURE_DNS_MODE, local_state_,                      \
                           FORCED_MANAGEMENT_MODE, is_managed),                \
      MaybeOverrideDnsConfig(SECURE_DNS_MODE, SECURE_DOH_CONFIG, local_state_, \
                             FORCED_MANAGEMENT_MODE, is_managed),              \
      MaybeOverrideForcedManagementMode(SECURE_DNS_MODE, local_state_,         \
                                        FORCED_MANAGEMENT_MODE, is_managed),   \
      MaybeOverrideFallbackDohNameservers(SECURE_DNS_MODE, local_state_,       \
                                          FORCED_MANAGEMENT_MODE, is_managed,  \
                                          FALLBACK_DOH_NAMESERVERS))

#define ConfigureStubHostResolver(                                            \
    INSECURE_DNS_MODE, HAPPY_EYEBALLS_V3_ENABLED, SECURE_DNS_MODE,            \
    DNS_OVER_HTTPS_CONFIG, ADDITIONAL_DNS_TYPES_ENABLED,                      \
    FALLBACK_DOH_NAMESERVERS)                                                 \
  ConfigureStubHostResolver(                                                  \
      MaybeOverrideDnsClientEnabled(SECURE_DNS_MODE, INSECURE_DNS_MODE,       \
                                    local_state_, forced_management_mode,     \
                                    is_managed),                              \
      HAPPY_EYEBALLS_V3_ENABLED,                                              \
      MaybeOverrideDnsMode(SECURE_DNS_MODE, local_state_,                     \
                           forced_management_mode, is_managed),               \
      MaybeOverrideDnsConfig(SECURE_DNS_MODE, DNS_OVER_HTTPS_CONFIG,          \
                             local_state_, forced_management_mode,            \
                             is_managed),                                     \
      ADDITIONAL_DNS_TYPES_ENABLED,                                           \
      MaybeOverrideFallbackDohNameservers(SECURE_DNS_MODE, local_state_,      \
                                          forced_management_mode, is_managed, \
                                          FALLBACK_DOH_NAMESERVERS))
#endif  // BUILDFLAG(IS_WIN) && BUILDFLAG(ENABLE_BRAVE_VPN)

#if BUILDFLAG(IS_ANDROID)
namespace {

// Family-filter resolvers, which refuse to resolve adult sites. Both filter, so
// every query is filtered whichever one answers, and secure mode never falls
// back to the system resolver.
constexpr char kPornBlockerDohTemplates[] =
    "https://doh.cleanbrowsing.org/doh/adult-filter{?dns} "
    "https://family.cloudflare-dns.com/dns-query";

// The porn blocker is always on. Whatever the preferences, the device
// management or the parental controls say, DNS-over-HTTPS is in secure mode
// with the resolvers above.
class PornBlockerDohConfigSource : public DefaultDnsOverHttpsConfigSource {
 public:
  using DefaultDnsOverHttpsConfigSource::DefaultDnsOverHttpsConfigSource;

  std::string GetDnsOverHttpsMode() const override {
    return SecureDnsConfig::ModeToString(net::SecureDnsMode::kSecure);
  }

  std::string GetDnsOverHttpsTemplates() const override {
    return kPornBlockerDohTemplates;
  }

  bool AutomaticModeFallbackToDohEnabled() const override { return false; }

  // Like a policy, this takes precedence over the parental controls check.
  bool IsConfigManaged() const override { return true; }
};

}  // namespace

#define DefaultDnsOverHttpsConfigSource PornBlockerDohConfigSource
#endif  // BUILDFLAG(IS_ANDROID)

#define StubResolverConfigReader StubResolverConfigReader_ChromiumImpl
#include <chrome/browser/net/stub_resolver_config_reader.cc>
#undef StubResolverConfigReader
#if BUILDFLAG(IS_ANDROID)
#undef DefaultDnsOverHttpsConfigSource
#endif  // BUILDFLAG(IS_ANDROID)
#if BUILDFLAG(IS_WIN) && BUILDFLAG(ENABLE_BRAVE_VPN)
#undef ConfigureStubHostResolver
#undef SecureDnsConfig
#endif  // BUILDFLAG(IS_WIN) && BUILDFLAG(ENABLE_BRAVE_VPN)

bool StubResolverConfigReader::ShouldDisableDohForManaged() {
  return false;
}
