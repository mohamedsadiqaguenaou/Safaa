/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "third_party/blink/renderer/core/workers/abstract_worker.h"

#include "third_party/blink/renderer/core/execution_context/execution_context.h"
#include "third_party/blink/renderer/core/frame/csp/content_security_policy.h"

#define CheckAllowedByCSPForNoThrow CheckAllowedByCSPForNoThrow_ChromiumImpl

#include <third_party/blink/renderer/core/workers/abstract_worker.cc>

#undef CheckAllowedByCSPForNoThrow

namespace blink {

bool AbstractWorker::CheckAllowedByCSPForNoThrow(const KURL& script_url) {
  // Scripts in an isolated world with an empty CSP of its own (Brave's
  // internal world, see cosmetic_filters_js_render_frame_observer.cc) are not
  // subject to the page's CSP; neither are the workers they start. Their
  // global scopes still get the page's CSP. Isolated worlds with a non-empty
  // CSP (extensions) keep the page CSP check.
  if (ExecutionContext* context = GetExecutionContext()) {
    ContentSecurityPolicy* world_csp =
        context->GetContentSecurityPolicyForCurrentWorld();
    if (world_csp && world_csp != context->GetContentSecurityPolicy() &&
        !world_csp->IsActive()) {
      return true;
    }
  }
  return CheckAllowedByCSPForNoThrow_ChromiumImpl(script_url);
}

}  // namespace blink
