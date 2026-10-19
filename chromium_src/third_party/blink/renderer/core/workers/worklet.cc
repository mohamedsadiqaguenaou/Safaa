/* Copyright (c) 2026 The Brave Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

#include "third_party/blink/renderer/core/workers/worklet.h"

#include "third_party/blink/renderer/core/execution_context/execution_context.h"
#include "third_party/blink/renderer/core/frame/csp/content_security_policy.h"
#include "third_party/blink/renderer/platform/bindings/dom_wrapper_world.h"
#include "third_party/blink/renderer/platform/bindings/script_state.h"

#define addModule addModule_ChromiumImpl

#include <third_party/blink/renderer/core/workers/worklet.cc>

#undef addModule

namespace blink {

ScriptPromise<IDLUndefined> Worklet::addModule(
    ScriptState* script_state,
    const String& module_url,
    const WorkletOptions* options,
    ExceptionState& exception_state) {
  // A worklet global scope gets the CSP of the window it is created for, and
  // its module scripts are fetched under that CSP. Scripts in an isolated
  // world with an empty CSP of its own (Brave's internal world, see
  // cosmetic_filters_js_render_frame_observer.cc) are not subject to the
  // page's CSP, so create the global scope right away, while that world's
  // script runs: ThreadedWorkletMessagingProxy::Initialize() then takes the
  // world's CSP (see threaded_worklet_messaging_proxy.cc). Otherwise the
  // global scope is created later from a task, with the page's CSP.
  ExecutionContext* context = GetExecutionContext();
  if (context && script_state) {
    ContentSecurityPolicy* world_csp =
        context->GetContentSecurityPolicyForWorld(&script_state->World());
    if (world_csp && world_csp != context->GetContentSecurityPolicy() &&
        !world_csp->IsActive()) {
      while (NeedsToCreateGlobalScope()) {
        proxies_.push_back(CreateGlobalScope());
      }
    }
  }
  return addModule_ChromiumImpl(script_state, module_url, options,
                                exception_state);
}

}  // namespace blink
